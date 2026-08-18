from sqlalchemy import Column, Integer, String, Text, Boolean, DateTime, func
from models import Base


class QuestionBank(Base):
    __tablename__ = "question_bank"

    id = Column(Integer, primary_key=True, autoincrement=True)
    question_type = Column(String(32), nullable=False, comment="题型")
    stem = Column(Text, nullable=False, comment="题干")
    stem_normalized = Column(Text, comment="归一化题干")
    question_image = Column(String(500), comment="题目图片路径")

    option_a = Column(Text, comment="选项A")
    option_a_image = Column(String(500))
    option_b = Column(Text, comment="选项B")
    option_b_image = Column(String(500))
    option_c = Column(Text, comment="选项C")
    option_c_image = Column(String(500))
    option_d = Column(Text, comment="选项D")
    option_d_image = Column(String(500))
    option_e = Column(Text, comment="选项E")
    option_e_image = Column(String(500))
    option_f = Column(Text, comment="选项F")
    option_f_image = Column(String(500))
    option_g = Column(Text, comment="选项G")
    option_g_image = Column(String(500))
    option_h = Column(Text, comment="选项H")
    option_h_image = Column(String(500))

    correct_answer = Column(String(100), comment="正确答案")
    explanation = Column(Text, comment="解析")
    explanation_image = Column(String(500))

    subject = Column(String(128), comment="科目")
    chapter = Column(String(256), comment="章节")

    created_at = Column(DateTime, server_default=func.now())
    updated_at = Column(DateTime, server_default=func.now(), onupdate=func.now())
