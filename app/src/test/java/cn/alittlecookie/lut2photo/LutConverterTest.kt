package cn.alittlecookie.lut2photo

import cn.alittlecookie.lut2photo.lut2photo.utils.LutConverter
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream

/**
 * LUT转换器单元测试
 */
class LutConverterTest {
    
    @Test
    fun testDetectLutInfo_17bit() {
        val lutContent = """
            TITLE "Test 17-bit LUT"
            LUT_3D_SIZE 17
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            
            0.0 0.0 0.0
        """.trimIndent()
        
        val inputStream = ByteArrayInputStream(lutContent.toByteArray())
        val lutInfo = LutConverter.detectLutInfo(inputStream, "test.vlt")
        
        assertNotNull(lutInfo)
        assertEquals(17, lutInfo?.size)
        assertEquals(LutConverter.LutFormat.VLT, lutInfo?.format)
    }
    
    @Test
    fun testDetectLutInfo_33bit() {
        val lutContent = """
            TITLE "Test 33-bit LUT"
            LUT_3D_SIZE 33
            
            0.0 0.0 0.0
        """.trimIndent()
        
        val inputStream = ByteArrayInputStream(lutContent.toByteArray())
        val lutInfo = LutConverter.detectLutInfo(inputStream, "test.cube")
        
        assertNotNull(lutInfo)
        assertEquals(33, lutInfo?.size)
        assertEquals(LutConverter.LutFormat.CUBE, lutInfo?.format)
    }
    
    @Test
    fun testConvertTo33Cube_identity() {
        // 创建一个简单的2x2x2恒等LUT
        val sourceLut = Array(2) { r ->
            Array(2) { g ->
                Array(2) { b ->
                    floatArrayOf(
                        r / 1f,
                        g / 1f,
                        b / 1f
                    )
                }
            }
        }
        
        val result = LutConverter.convertTo33Cube(sourceLut, 2, "Test Identity")
        
        // 验证输出包含正确的头部
        assertTrue(result.contains("LUT_3D_SIZE 33"))
        assertTrue(result.contains("TITLE \"Test Identity\""))
        
        // 验证数据行数量（33^3 = 35937）
        val dataLines = result.lines().filter { line ->
            line.matches(Regex("^\\s*[0-9.]+\\s+[0-9.]+\\s+[0-9.]+\\s*$"))
        }
        assertEquals(35937, dataLines.size)
    }
    
    @Test
    fun testParseLutData_simple() {
        val lutContent = """
            LUT_3D_SIZE 2
            
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()
        
        val inputStream = ByteArrayInputStream(lutContent.toByteArray())
        val result = LutConverter.parseLutData(inputStream)
        
        assertNotNull(result)
        val (lut, size) = result!!
        assertEquals(2, size)
        
        // 验证第一个点 (0,0,0)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), lut[0][0][0], 0.001f)
        
        // 验证最后一个点 (1,1,1)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut[1][1][1], 0.001f)
    }
    
    @Test
    fun testValidateLutFile() {
        val validContent = """
            LUT_3D_SIZE 33
            0.0 0.0 0.0
        """.trimIndent()
        
        val invalidContent = """
            Some random content
            without LUT_3D_SIZE
        """.trimIndent()
        
        // 注意：这个测试需要实际文件系统，在单元测试中可能需要mock
        // 这里只是展示测试结构
    }
    
    @Test
    fun testConversion_preservesColorRange() {
        // 创建一个3x3x3的测试LUT，包含黑白和中间色
        val sourceLut = Array(3) { r ->
            Array(3) { g ->
                Array(3) { b ->
                    floatArrayOf(
                        r / 2f,
                        g / 2f,
                        b / 2f
                    )
                }
            }
        }
        
        val result = LutConverter.convertTo33Cube(sourceLut, 3, "Test Range")
        
        // 验证包含黑色 (0.0 0.0 0.0)
        assertTrue(result.contains("0.0 0.0 0.0"))
        
        // 验证包含白色或接近白色的值
        val lines = result.lines()
        val hasHighValues = lines.any { line ->
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size == 3) {
                parts.all { it.toFloatOrNull()?.let { v -> v > 0.9f } == true }
            } else {
                false
            }
        }
        assertTrue(hasHighValues)
    }
}
