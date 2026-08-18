package com.lk.studyassistant.quantum

import com.lk.studyassistant.quantum.util.QuestionType
import com.lk.studyassistant.quantum.util.QuestionTypeClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun parsesSample117WithoutPuttingAIntoStem() {
        val result = QuestionTypeClassifier.parseForTest(
            """
            单选 117、小队长在每日交接班会中的主要职责是什么？
            A.编制巡查方案并组织实施
            B.负责所辖区段保护区视频监控覆盖区域风险评估
            C.负责保护区内地勘、降水、打桩、钻孔类施工现场点位复核
            D.及时传达保护区管控要点、工作要求，实时解决巡查相关问题，并填写“交接班会记录表”
            """.trimIndent()
        )

        assertEquals(QuestionType.SINGLE_CHOICE, result.questionType)
        assertEquals("第117题", result.questionNumber)
        assertEquals("小队长在每日交接班会中的主要职责是什么？", result.questionText)
        assertEquals("编制巡查方案并组织实施", result.options.first { it.label == "A" }.text)
        assertFalse(result.questionText.contains("编制巡查方案"))
    }

    @Test
    fun parsesSample118WithFourOptions() {
        val result = QuestionTypeClassifier.parseForTest(
            """
            单选 118、在地铁控制保护区内进行建设或施工的单位或应当收巡查大队发放的()
            A.告知函
            B.合同书
            C.施工许可证
            D.安全检查报告
            """.trimIndent()
        )

        assertEquals(QuestionType.SINGLE_CHOICE, result.questionType)
        assertEquals("第118题", result.questionNumber)
        assertEquals(listOf("A", "B", "C", "D"), result.options.map { it.label })
    }

    @Test
    fun parsesSample112ShortNumericOptions() {
        val result = QuestionTypeClassifier.parseForTest(
            """
            单选 112、根据高处作业相关规定，作业高度（以距坠落高度基准面为基准）在几米及以上的作业，为高处作业？（）
            A.3
            B.1
            C.2
            D.4
            """.trimIndent()
        )

        assertEquals(QuestionType.SINGLE_CHOICE, result.questionType)
        assertEquals("第112题", result.questionNumber)
        assertEquals("3", result.options.first { it.label == "A" }.text)
        assertEquals("1", result.options.first { it.label == "B" }.text)
        assertEquals("2", result.options.first { it.label == "C" }.text)
        assertEquals("4", result.options.first { it.label == "D" }.text)
    }

    @Test
    fun parsesSample113WithoutMergingNextOption() {
        val result = QuestionTypeClassifier.parseForTest(
            """
            单选 113、关于区段巡查的通行方式，以下哪些描述是不正确的？（）
            A.区段巡查一般采用骑行电动自行车方式开展
            B.区段巡查必须采用机动车巡查方式开展
            C.特殊区域可采用轨道交通方式开展
            D.特殊区域可采用步行方式开展
            """.trimIndent()
        )

        assertEquals(QuestionType.SINGLE_CHOICE, result.questionType)
        assertEquals("区段巡查一般采用骑行电动自行车方式开展", result.options.first { it.label == "A" }.text)
        assertEquals("区段巡查必须采用机动车巡查方式开展", result.options.first { it.label == "B" }.text)
        assertFalse(result.options.first { it.label == "A" }.text.contains("机动车"))
    }

}
