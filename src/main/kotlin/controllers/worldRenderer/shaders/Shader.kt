package controllers.worldRenderer.shaders

import org.lwjgl.opengl.GL11C.GL_FALSE
import org.lwjgl.opengl.GL11C.GL_TRUE
import org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL20C.GL_LINK_STATUS
import org.lwjgl.opengl.GL20C.GL_VALIDATE_STATUS
import org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL20C.glAttachShader
import org.lwjgl.opengl.GL20C.glCompileShader
import org.lwjgl.opengl.GL20C.glCreateProgram
import org.lwjgl.opengl.GL20C.glCreateShader
import org.lwjgl.opengl.GL20C.glDeleteProgram
import org.lwjgl.opengl.GL20C.glDeleteShader
import org.lwjgl.opengl.GL20C.glDetachShader
import org.lwjgl.opengl.GL20C.glGetProgramInfoLog
import org.lwjgl.opengl.GL20C.glGetProgrami
import org.lwjgl.opengl.GL20C.glGetShaderInfoLog
import org.lwjgl.opengl.GL20C.glGetShaderi
import org.lwjgl.opengl.GL20C.glLinkProgram
import org.lwjgl.opengl.GL20C.glShaderSource
import org.lwjgl.opengl.GL20C.glValidateProgram
import org.lwjgl.opengl.GL43C.GL_COMPUTE_SHADER

class Shader {
    private val units: MutableList<Unit> = ArrayList()

    internal class Unit(internal val type: Int, internal val filename: String)

    fun add(type: Int, name: String): Shader {
        units.add(Unit(type, name))
        return this
    }

    @Throws(ShaderException::class)
    fun compile(template: Template): Int {
        val program = glCreateProgram()
        val shaders = IntArray(units.size)
        var i = 0
        var ok = false
        try {
            while (i < shaders.size) {
                val unit = units[i]
                val shader = glCreateShader(unit.type)
                if (shader == 0)
                    throw ShaderException("Unable to create shader of type ${unit.type}")
                val processedFile = template.load(unit.filename)
                val source = processedFile.contents
                glShaderSource(shader, source)
                glCompileShader(shader)
                if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
                    val err: String = glGetShaderInfoLog(shader)
                    glDeleteShader(shader)
                    throw ShaderException(reformatErrorString(err, processedFile))
                }
                glAttachShader(program, shader)
                shaders[i++] = shader
            }
            glLinkProgram(program)
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                val err: String = glGetProgramInfoLog(program)
                throw ShaderException(err)
            }
            glValidateProgram(program)
            if (glGetProgrami(program, GL_VALIDATE_STATUS) == GL_FALSE) {
                val err: String = glGetProgramInfoLog(program)
                throw ShaderException(err)
            }
            ok = true
        } finally {
            while (i > 0) {
                val shader = shaders[--i]
                glDetachShader(program, shader)
                glDeleteShader(shader)
            }
            if (!ok) {
                glDeleteProgram(program)
            }
        }
        return program
    }

    private fun reformatErrorString(
        err: String,
        processedFile: Template.ProcessedFile
    ): String =
        // Extract and transform line numbers from error
        err.split('\n').joinToString("\n") {
            val match = ERROR_LINE_REGEX.matchEntire(it)
            if (match != null) {
                val (line, column, message) = match.destructured
                val lineNumber = line.toInt()
                val columnNumber = column.toInt()
                val lineNumInfo = processedFile.getLineNumber(lineNumber)
                "${lineNumInfo.filename}:${lineNumInfo.logicalLine}($columnNumber):$message"
            } else {
                it
            }
        }

    companion object {
        // Line format: `0:123(10): error: ...`
        private val ERROR_LINE_REGEX = Regex("0:(\\d+)\\((\\d+)\\): (.*)")
        const val VERSION_HEADER = "#version 430\n"
        val PROGRAM = lazy {
            Shader()
                .add(GL_VERTEX_SHADER, "/gpu/vert.glsl")
                .add(GL_FRAGMENT_SHADER, "/gpu/frag.glsl")
        }
        val COMPUTE_PROGRAM = lazy {
            Shader()
                .add(GL_COMPUTE_SHADER, "/gpu/comp.glsl")
        }
        val UNORDERED_COMPUTE_PROGRAM = lazy {
            Shader()
                .add(GL_COMPUTE_SHADER, "/gpu/comp_unordered.glsl")
        }

        fun createTemplate(threadCount: Int, facesPerThread: Int): Template {
            val versionHeader: String = VERSION_HEADER
            val template = Template()
            template.add { key ->
                if ("version_header" == key) {
                    return@add versionHeader
                }
                if ("thread_config" == key) {
                    return@add """
                #define THREAD_COUNT $threadCount
                #define FACES_PER_THREAD $facesPerThread
                
                    """.trimIndent()
                }
                null
            }
            template.addInclude(Shader::class.java)
            return template
        }
    }
}
