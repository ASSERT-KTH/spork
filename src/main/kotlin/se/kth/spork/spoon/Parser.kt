package se.kth.spork.spoon

import se.kth.spork.spoon.printer.SourceExtractor
import se.kth.spork.spoon.printer.SporkPrettyPrinter
import se.kth.spork.util.LazyLogger
import spoon.Launcher
import spoon.compiler.Environment
import spoon.reflect.CtModel
import spoon.reflect.code.CtComment
import spoon.reflect.cu.CompilationUnit
import spoon.reflect.declaration.*
import spoon.support.compiler.FileSystemFile
import spoon.support.compiler.VirtualFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.function.Consumer
import java.util.function.Function
import java.util.stream.Stream

/**
 * A class for dealing with parsing.
 *
 * @author Simon Larsén
 */
object Parser {
    const val IMPORT_STATEMENTS = "spork_import_statements"
    const val COMPILATION_UNIT_COMMENT = "spork_cu_comment"
    private val LOGGER = LazyLogger(Parser::class.java)

    /**
     * Parse a Java file to a Spoon tree. Any import statements in the file are attached to the returned module's
     * metadata with the [Parser.IMPORT_STATEMENTS] key. The imports are sorted in ascending lexicographical
     * order.
     *
     * @param javaFile Path to a Java file.
     * @return The root module of the Spoon tree.
     */
    fun parse(javaFile: Path): CtModule {
        // Reading from a virtual file is a workaround to a bug in Spoon
        // Sometimes, the class comment is dropped when reading from the file system
        return parse(Consumer { launcher: Launcher -> launcher.addInputResource(FileSystemFile(javaFile.toFile())) })
    }

    /**
     * Parse the contents of a single Java file.
     *
     * @param javaFileContents The contents of a single Java file.
     * @return The root module of the Spoon tree.
     */
    fun parse(javaFileContents: String?): CtModule {
        return parse(Consumer { launcher: Launcher -> launcher.addInputResource(VirtualFile(javaFileContents)) })
    }

    /**
     * Parse a Java file to a Spoon tree. Any import statements in the file are attached to the returned module's
     * metadata with the [Parser.IMPORT_STATEMENTS] key. The imports are sorted in ascending lexicographical
     * order.
     *
     * Comments are ignored
     *
     * @param javaFile Path to a Java file.
     * @return The root module of the Spoon tree.
     */
    fun parseWithoutComments(javaFile: Path): CtModule {
        return parse(Consumer { launcher: Launcher ->
            launcher.environment.setCommentEnabled(false)
            launcher.addInputResource(FileSystemFile(javaFile.toFile()))
        })
    }

    fun setSporkEnvironment(env: Environment, tabulationSize: Int, useTabs: Boolean) {
        env.tabulationSize = tabulationSize
        env.useTabulations(useTabs)
        env.setPrettyPrinterCreator { SporkPrettyPrinter(env) }
        env.noClasspath = true
    }

    private fun parse(addResource: Consumer<Launcher>): CtModule {
        val launcher = Launcher()
        addResource.accept(launcher)
        val model = launcher.buildModel()
        val indentationGuess = SourceExtractor.guessIndentation(model)
        val indentationType = if (indentationGuess.second) "tabs" else "spaces"
        LOGGER.info { "Using indentation: " + indentationGuess.first + " " + indentationType }
        setSporkEnvironment(launcher.environment, indentationGuess.first, indentationGuess.second)
        val module = model.unnamedModule
        module.putMetadata<CtElement>(COMPILATION_UNIT_COMMENT, getCuComment(module))

        // TODO preserve order of import statements
        val imports = parseImportStatements(model).toList().sortedBy(CtImport::prettyprint);
        module.putMetadata<CtElement>(IMPORT_STATEMENTS, imports)
        return module
    }

    // FIXME This is an ugly workaround for merging compliation unit comments
    private fun getCuComment(module: CtModule) =
            module.factory.CompilationUnit().map.values.firstOrNull()?.comments?.firstOrNull()?.rawContent ?: ""

    /**
     * Parse unique import statements from all types of the given model.
     *
     * Obviously, as a set is returned, the order of the statements is not preserved.
     *
     * @param model A model.
     * @return A list of import statements.
     */
    fun parseImportStatements(model: CtModel): Set<CtImport> {
        val importStatements: MutableSet<CtImport> = HashSet()
        for (type in model.allTypes) {
            importStatements.addAll(
                    parseImportStatements(type)
            )
        }
        return importStatements
    }

    /**
     * Parse import statements from the given type. Note that all types in a single file will get the same
     * import statements attached to them, so there is no need to parse imports from multiple types in a single
     * file.
     *
     * The order of the import statements is preserved.
     *
     * @param type A Java type.
     * @return A list of import statements.
     */
    fun parseImportStatements(type: CtType<*>): List<CtImport> {
        val cu: CtCompilationUnit = type.factory.CompilationUnit().getOrCreate(type)
        return cu.imports
    }

    /**
     * Read the contents of a file.
     *
     * @param path Path to a file.
     * @return The contents of the file.
     */
    fun read(path: Path): String {
        return try {
            java.lang.String.join("\n", Files.readAllLines(path))
        } catch (e: IOException) {
            e.printStackTrace()
            throw RuntimeException("Error reading from $path")
        }
    }
}