package com.lk.studyassistant.quantum

import com.lk.studyassistant.quantum.local.LocalQuestionParser
import com.lk.studyassistant.quantum.util.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 题型判定回归测试（1.1.42）。
 *
 * 这三类误判在收敛到"只支持单选/多选/判断"之后代价最大：题型标错了，
 * 题库侧判断题与选择题严格互斥，一标错这道题就永远匹配不上。
 */
class QuestionTypeDetectionTest {

    /**
     * 括号占位符不能改变题型。
     *
     * 旧版 detectType 把 `text.contains("( )")` 排在单选判定之前，而
     * TextNormalizer 会把全角"（ ）"转成半角"( )"，于是一道有 4 个选项的
     * 单选题被判成填空题 → 题型不兼容 → 题库必然 miss。
     */
    @Test
    fun 单选题带括号占位符不应被判成填空或其它题型() {
        val result = LocalQuestionParser.parse(
            """
            在地铁控制保护区内进行建设或施工的单位应当收取巡查大队发放的（  ）
            A.告知函
            B.合同书
            C.施工许可证
            D.安全检查报告
            """.trimIndent()
        )

        assertEquals(QuestionType.SINGLE_CHOICE, result.questionType)
        assertEquals(4, result.options.size)
    }

    /** 下划线占位符同理，不能把带选项的选择题拖成别的题型。 */
    @Test
    fun 单选题带下划线占位符仍是单选() {
        val result = LocalQuestionParser.parse(
            """
            高处作业的高度基准面标准为____米及以上
            A.2
            B.3
            C.4
            D.5
            """.trimIndent()
        )

        assertEquals(QuestionType.SINGLE_CHOICE, result.questionType)
    }

    /**
     * 题干里出现"判断"两个字不能把选择题打成判断题。
     * 旧版 `text.contains("判断")` 排在选项判定之前，医学/工程类题干极常见这个词。
     */
    @Test
    fun 题干含判断二字的多选项题应判为选择题而非判断题() {
        val result = LocalQuestionParser.parse(
            """
            护士判断该患者处于休克代偿期的主要依据是什么
            A.血压下降
            B.脉压差缩小
            C.意识丧失
            D.尿量正常
            """.trimIndent()
        )

        assertEquals(QuestionType.SINGLE_CHOICE, result.questionType)
    }

    /** 有明确"多选题"标签且选项多于 3 个时，应判为多选而不是被结构规则压成单选。 */
    @Test
    fun 多选题标签优先于默认单选() {
        val result = LocalQuestionParser.parse(
            """
            多选题 下列属于高处作业防护措施的有哪些
            A.佩戴安全带
            B.设置防护栏
            C.悬挂安全网
            D.夜间照明
            """.trimIndent()
        )

        assertEquals(QuestionType.MULTIPLE_CHOICE, result.questionType)
    }

    /** 正确/错误二元选项 → 判断题（屏幕上的"判断题"标签常被白名单边界剥离）。 */
    @Test
    fun 正确错误二元选项判为判断题() {
        val result = LocalQuestionParser.parse(
            """
            区段巡查一般采用骑行电动自行车方式开展
            A.正确
            B.错误
            """.trimIndent()
        )

        assertEquals(QuestionType.TRUE_FALSE, result.questionType)
    }

    /** 独立的"判断题"标签 + 无选项，仍应判为判断题。 */
    @Test
    fun 判断题标签无选项时仍判为判断题() {
        val result = LocalQuestionParser.parse(
            """
            判断题
            保护区内施工必须经过巡查大队复核
            """.trimIndent()
        )

        assertEquals(QuestionType.TRUE_FALSE, result.questionType)
    }
}
