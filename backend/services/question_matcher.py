from difflib import SequenceMatcher
from sqlalchemy.orm import Session
from models.question_bank import QuestionBank
from services.excel_importer import normalize_text


def match_question(
    db: Session,
    question_type: str,
    stem: str,
    options: dict[str, str],
    subject: str = ""
) -> dict | None:
    """
    四级优先级匹配：
    1. 题型 + 归一化题干完全匹配
    2. 题型 + 原文题干精确匹配
    3. 题型 + 题干相似 + 选项联合匹配
    4. 题型 + 题干相似度排序匹配
    """
    type_key = question_type_map(question_type)
    stem_norm = normalize_text(stem)

    # Level 1: 题型 + 归一化题干精确匹配
    q = (
        db.query(QuestionBank)
        .filter(
            QuestionBank.question_type == type_key,
            QuestionBank.stem_normalized == stem_norm
        )
        .first()
    )
    if q:
        return build_result(q, "题型+题干精确匹配", 1.0)

    # Level 2: 题型 + 原文精确匹配
    q = (
        db.query(QuestionBank)
        .filter(
            QuestionBank.question_type == type_key,
            QuestionBank.stem == stem
        )
        .first()
    )
    if q:
        return build_result(q, "题型+原文精确匹配", 0.99)

    # Collect candidates by subject or all
    if subject:
        candidates = (
            db.query(QuestionBank)
            .filter(
                QuestionBank.question_type == type_key,
                QuestionBank.subject == subject
            )
            .all()
        )
    else:
        candidates = (
            db.query(QuestionBank)
            .filter(QuestionBank.question_type == type_key)
            .all()
        )

    if not candidates:
        return None

    # Level 3: 题干相似度 > 0.85 + 选项重叠率检查
    best_l3 = None
    best_l3_score = 0.0
    for c in candidates:
        stem_sim = SequenceMatcher(None, stem_norm, c.stem_normalized or "").ratio()
        if stem_sim < 0.85:
            continue
        opt_overlap = option_overlap_score(options, c)
        combined = stem_sim * 0.7 + opt_overlap * 0.3
        if combined > best_l3_score:
            best_l3_score = combined
            best_l3 = c
    if best_l3 and best_l3_score >= 0.88:
        return build_result(best_l3, f"题干+选项联合匹配(相似度{best_l3_score:.2f})", best_l3_score)

    # Level 4: 题干相似度排序匹配
    best_l4 = None
    best_l4_score = 0.0
    for c in candidates:
        sim = SequenceMatcher(None, stem_norm, c.stem_normalized or "").ratio()
        if sim > best_l4_score:
            best_l4_score = sim
            best_l4 = c
    if best_l4 and best_l4_score >= 0.6:
        return build_result(best_l4, f"题干相似度匹配(相似度{best_l4_score:.2f})", best_l4_score)

    return None


def question_type_map(qt: str) -> str:
    """映射AI返回的题型到Excel模板题型名"""
    t = qt.strip()
    if "多选" in t and "不定" not in t:
        return "多选题"
    if "不定" in t:
        return "不定项选择题"
    if "单选" in t:
        return "单选题"
    if "判断" in t:
        return "判断题"
    if "填空" in t:
        return "填空题"
    if "排序" in t:
        return "排序题"
    if "简答" in t:
        return "简答题"
    if "材料" in t:
        return "材料题"
    if "案例" in t:
        return "案例题"
    if "论述" in t:
        return "论述题"
    return t


def option_overlap_score(options: dict[str, str], db_q: QuestionBank) -> float:
    """计算选项重叠率"""
    db_options = {
        "A": db_q.option_a or "",
        "B": db_q.option_b or "",
        "C": db_q.option_c or "",
        "D": db_q.option_d or "",
        "E": db_q.option_e or "",
        "F": db_q.option_f or "",
        "G": db_q.option_g or "",
        "H": db_q.option_h or "",
    }
    db_options = {k: v for k, v in db_options.items() if v}
    if not db_options or not options:
        return 0.0

    scores = []
    for label, text in options.items():
        if label in db_options:
            db_text = db_options[label]
            s = SequenceMatcher(None, normalize_text(text), normalize_text(db_text)).ratio()
            scores.append(s)
    if not scores:
        return 0.0
    return sum(scores) / len(scores)


def build_result(db_q: QuestionBank, detail: str, confidence: float) -> dict:
    return {
        "answer": db_q.correct_answer,
        "source": "题库命中",
        "confidence": round(confidence, 3),
        "matchDetail": detail,
        "matchedQuestion": {
            "questionType": db_q.question_type,
            "stem": db_q.stem,
            "subject": db_q.subject,
            "chapter": db_q.chapter,
            "explanation": db_q.explanation,
            "options": {
                k: v for k, v in {
                    "A": db_q.option_a, "B": db_q.option_b,
                    "C": db_q.option_c, "D": db_q.option_d,
                    "E": db_q.option_e, "F": db_q.option_f,
                    "G": db_q.option_g, "H": db_q.option_h,
                }.items() if v
            }
        }
    }
