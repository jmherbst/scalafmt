package org.scalafmt.cli

import java.io.{
  ByteArrayInputStream,
  ByteArrayOutputStream,
  IOException,
  PrintStream
}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import munit.FunSuite
import org.scalafmt.Error.NoMatchingFiles
import org.scalafmt.Versions
import org.scalafmt.cli.FileTestOps._
import org.scalafmt.config.{Config, ProjectFiles, ScalafmtConfig}
import org.scalafmt.util.{AbsoluteFile, FileOps}
import org.scalafmt.util.OsSpecific._

abstract class AbstractCliTest extends FunSuite {
  def mkArgs(str: String): Array[String] =
    str.split(' ')

  def runWith(root: AbsoluteFile, argStr: String): Unit = {
    val args = mkArgs(argStr)
    val opts = getMockOptions(root)

    val conf = Cli.getConfig(args, opts)
    Cli.run(conf.get)
  }

  def getConfig(args: Array[String]): CliOptions = {
    Cli.getConfig(args, baseCliOptions).get
  }

  def assertContains(out: String, expected: String) = {
    assert(
      CliTest.stripCR(out).contains(CliTest.stripCR(expected)),
      out + "\n should have contained \n" + expected
    )
  }

  val unformatted = """
    |object a    extends   App {
    |pr("h")
    |}
                    """.stripMargin
  // Using maxColumn 10 just to see the CLI uses the custom style.
  val expected10 = """|object a
    |    extends App {
    |  pr(
    |    "h"
    |  )
    |}
    |""".stripMargin
  val formatted = """|object a extends App {
    |  pr("h")
    |}
    |""".stripMargin
  val customConfig =
    """
      |maxColumn   = 2
      """.stripMargin
  val sbtOriginal =
    """|lazy val x = project
      |   lazy val y    = project
      |   """.stripMargin

  val sbtExpected =
    """|lazy val x =
      |  project
      |lazy val y =
      |  project
      |""".stripMargin

  def gimmeConfig(string: String): ScalafmtConfig =
    Config.fromHoconString(string).get

  def noArgTest(
      input: AbsoluteFile,
      expected: String,
      cmds: Seq[Array[String]],
      assertExit: ExitCode => Unit = { exit => assert(exit.isOk, exit) },
      assertOut: String => Unit = { _ => {} }
  ): Unit = {
    cmds.foreach { args =>
      val out = new ByteArrayOutputStream()
      val init: CliOptions = getMockOptions(input, input, new PrintStream(out))
      val config = Cli.getConfig(args, init).get
      val exit = Cli.run(config)
      assertExit(exit)
      val obtained = dir2string(input)
      assertNoDiff(obtained, expected)
      val testConfig = config.copy(writeModeOpt = None)
      Cli.run(Cli.getConfig(Array("--test"), testConfig).get)
      assertOut(out.toString())
    }

  }

}

trait CliTestBehavior { this: AbstractCliTest =>
  def testCli(version: String) {
    val label = if (version == Versions.version) "core" else "dynamic"
    val dialectError =
      if (version == Versions.version) " [dialect default]" else ""
    test(s"scalafmt tmpFile tmpFile2: $label") {
      val originalTmpFile = Files.createTempFile("prefix", ".scala")
      val originalTmpFile2 = Files.createTempFile("prefix2", ".scala")
      val scalafmtConfig = Files.createTempFile("scalafmtConfig", ".scala")
      val config = s"""
        |version="$version"
        |maxColumn=7
        |style=IntelliJ
    """.stripMargin
      Files.write(originalTmpFile, unformatted.getBytes)
      Files.write(originalTmpFile2, unformatted.getBytes)
      Files.write(scalafmtConfig, config.getBytes)
      val args = Array(
        "--config",
        scalafmtConfig.toFile.getPath,
        originalTmpFile.toFile.getPath,
        originalTmpFile2.toFile.getPath
      )
      val formatInPlace = getConfig(args)
      Cli.run(formatInPlace)
      val obtained = FileOps.readFile(originalTmpFile.toString)
      val obtained2 = FileOps.readFile(originalTmpFile2.toString)
      assertNoDiff(obtained, expected10)
      assertNoDiff(obtained2, expected10)
    }

    test(s"scalafmt --stdout tmpFile prints to stdout: $label") {
      val originalTmpFile = Files.createTempFile("prefix", ".scala")
      Files.write(originalTmpFile, unformatted.getBytes)
      val args = Array(
        "--stdout",
        "--config-str",
        s"""{version="$version",style=IntelliJ}""",
        originalTmpFile.toFile.getPath
      )
      val baos = new ByteArrayOutputStream()
      val ps = new PrintStream(baos)
      val init = baseCliOptions.copy(
        common = baseCliOptions.common.copy(out = ps)
      )
      val auto = Cli.getConfig(args, init).get
      Cli.run(auto)
      val obtained = new String(baos.toByteArray, StandardCharsets.UTF_8)
      assertNoDiff(obtained, formatted)
      assert(obtained.length == formatted.length)
    }

    test(s"scalafmt --stdin --assume-filename: $label") {
      val scalafmtConfig = Files.createTempFile(".scalafmt", ".conf")
      val config = s"""
        |version="$version"
        |maxColumn=7
        |style=IntelliJ
    """.stripMargin
      Files.write(scalafmtConfig, config.getBytes)

      val args = Array(
        "--stdin",
        "--assume-filename",
        "build.sbt",
        "--config",
        scalafmtConfig.toFile.getPath
      )
      val printToStdout = getConfig(args)
      val bais = new ByteArrayInputStream(sbtOriginal.getBytes)
      val baos = new ByteArrayOutputStream()
      val ps = new PrintStream(baos)
      Cli.run(
        printToStdout.copy(
          common = printToStdout.common.copy(
            out = ps,
            in = bais
          )
        )
      )
      val obtained = new String(baos.toByteArray, StandardCharsets.UTF_8)
      assertNoDiff(obtained, sbtExpected)
      assert(obtained.size == sbtExpected.size)
    }

    test(s"scalafmt --test tmpFile is left unformatted: $label") {
      val tmpFile = Files.createTempFile("prefix", ".scala")
      Files.write(tmpFile, unformatted.getBytes)
      val args = Array(
        tmpFile.toFile.getPath,
        "--test",
        "--config-str",
        s"""{version="$version",style=IntelliJ}"""
      )
      val formatInPlace = getConfig(args)
      val exit = Cli.run(formatInPlace)
      assert(exit.is(ExitCode.TestError))
      val str = FileOps.readFile(tmpFile.toString)
      assertNoDiff(str, unformatted)
    }
    test(s"scalafmt --test fails with non zero exit code $label") {
      val tmpFile = Files.createTempFile("prefix", ".scala")
      Files.write(tmpFile, unformatted.getBytes)
      val args = Array(
        tmpFile.toFile.getPath,
        "--test",
        "--config-str",
        s"""{version="$version",style=IntelliJ, docstring = Asterisk}"""
      )
      val formatInPlace = getConfig(args)
      val exit = Cli.run(formatInPlace)
      assert(exit.is(ExitCode.UnexpectedError))
    }

    test(s"scalafmt foo.randomsuffix is formatted: $label") {
      val tmpFile = Files.createTempFile("prefix", "randomsuffix")
      Files.write(tmpFile, unformatted.getBytes)
      val args = Array(
        "--config-str",
        s"""{version="$version",style=IntelliJ}""",
        tmpFile.toFile.getAbsolutePath
      )
      Cli.exceptionThrowingMainWithOptions(args, baseCliOptions)
      val obtained = FileOps.readFile(tmpFile.toString)
      // TODO: We need to pass customFiles information to ProjectFiles
      assertNoDiff(obtained, formatted)
    }

    test(s"handles .scala, .sbt, and .sc files: $label") {
      val input = string2dir(
        s"""|/foobar.scala
          |object    A {  }
          |/foo.sbt
          |lazy   val x   = project
          |/foo.sc
          |lazy   val x   = project
          |""".stripMargin
      )
      val expected =
        s"""|/foo.sbt
          |lazy val x = project
          |
          |/foo.sc
          |lazy val x = project
          |
          |/foobar.scala
          |object A {}
          |""".stripMargin
      val options = getConfig(
        Array(
          input.path,
          "--config-str",
          s"""{version="$version",style=IntelliJ}"""
        )
      )
      Cli.run(options)
      val obtained = dir2string(input)
      assertNoDiff(obtained, expected)
    }

    test(s"excludefilters are respected: $label") {
      val input = string2dir(
        s"""|/foo.sbt
          |lazy   val x   = project
          |
          |/target/FormatMe.scala
          |object    PleaseFormatMeOtherwiseIWillBeReallySad   {  }
          |
          |/target/nested1/DoNotFormatMe.scala
          |object    AAAAAAIgnoreME   {  }
          |
          |/target/nested1/nested2/DoNotFormatMeToo.scala
          |object    BBBBBBIgnoreME   {  }
          |
          |/target/nested3/DoNotFormatMe.scala
          |object    CIgnoreME   {  }
          |""".stripMargin
      )
      val expected =
        s"""|/foo.sbt
          |lazy val x = project
          |
          |/target/FormatMe.scala
          |object PleaseFormatMeOtherwiseIWillBeReallySad {}
          |
          |/target/nested1/DoNotFormatMe.scala
          |object    AAAAAAIgnoreME   {  }
          |
          |/target/nested1/nested2/DoNotFormatMeToo.scala
          |object    BBBBBBIgnoreME   {  }
          |
          |/target/nested3/DoNotFormatMe.scala
          |object    CIgnoreME   {  }
          |""".stripMargin
      val options = getConfig(
        Array(
          "--config-str",
          s"""{version="$version",style=IntelliJ}""",
          input.path,
          "--exclude",
          "target/nested".asFilename
        )
      )

      Cli.run(options)

      val obtained = dir2string(input)
      assertNoDiff(obtained, expected)

    }

    test(s"scalafmt doesnotexist.scala throws error: $label") {
      def check(filename: String): Unit = {
        val args = Array(
          s"$filename.scala".asFilename,
          "--config-str",
          s"""{version="$version",style=IntelliJ}"""
        )
        intercept[IOException] {
          Cli.exceptionThrowingMainWithOptions(args, baseCliOptions)
        }
      }
      check("notfound")
      check("target/notfound")
    }

    test(s"scalafmt (no matching files) throws error: $label") {
      val scalafmtConfig: Path = Files.createTempFile(".scalafmt", ".conf")
      val config: String = s"""
        |version="$version"
               """.stripMargin
      Files.write(scalafmtConfig, config.getBytes)
      val options = baseCliOptions.copy(config = Some(scalafmtConfig.toFile))
      intercept[NoMatchingFiles.type] {
        Cli.run(options)
      }
    }

    test(
      s"scalafmt (no matching files) is okay with --mode diff and --stdin: $label"
    ) {
      val diff = getConfig(
        Array(
          "--mode",
          "diff",
          "--config-str",
          s"""{version="$version",style=IntelliJ}"""
        )
      )
      val stdin = getConfig(
        Array(
          "--stdin",
          "--config-str",
          s"""{version="$version",style=IntelliJ}"""
        )
      ).copy(
        common = CommonOptions(in = new ByteArrayInputStream("".getBytes))
      )
      Cli.run(diff)
      Cli.run(stdin)
    }

    test(s"scalafmt (no arg) read config from git repo: $label") {
      val input = string2dir(
        s"""|/foo.scala
          |object    FormatMe {
          |  val x = 1
          |}
          |/target/foo.scala
          |object A   { }
          |
          |/.scalafmt.conf
          |version = "$version"
          |maxColumn = 2
          |project.excludeFilters = [target]
          |""".stripMargin
      )

      val expected =
        s"""|/.scalafmt.conf
          |version = "$version"
          |maxColumn = 2
          |project.excludeFilters = [target]
          |
          |/foo.scala
          |object FormatMe {
          |  val x =
          |    1
          |}
          |
          |/target/foo.scala
          |object A   { }
          |""".stripMargin
      noArgTest(
        input,
        expected,
        Seq(Array.empty[String], Array("--mode", "diff"))
      )
    }

    test(s"scalafmt (no arg, no config): $label") {
      noArgTest(
        string2dir(
          """|/foo.scala
            |object    FormatMe
            |/foo.sc
            |object    FormatMe
            |""".stripMargin
        ),
        """|/foo.sc
          |object FormatMe
          |
          |/foo.scala
          |object FormatMe
          |""".stripMargin,
        Seq(
          Array("--config-str", s"""{version="$version"}""")
        )
      )
    }

    test(s"config is read even from nested dir: $label") {
      val original = "object a { val x = 1 }"
      val expected =
        """|object a {
          |  val x =
          |    1
          |}
          |""".stripMargin
      val input = string2dir(
        s"""|/nested/foo.scala
          |$original
          |/.scalafmt.conf
          |version="$version"
          |maxColumn = 2
          |""".stripMargin
      )
      val workingDir = input / "nested"
      val options: CliOptions = {
        val mock = getMockOptions(input, workingDir)
        mock.copy(common = mock.common.copy(cwd = workingDir))
      }
      val config = Cli.getConfig(Array("foo.scala"), options).get
      Cli.run(config)
      val obtained = FileOps.readFile(workingDir / "foo.scala")
      assertNoDiff(obtained, expected)
    }

    test(
      s"if project.includeFilters isn't modified (and files aren't passed manually), it should ONLY accept scala and sbt files: $label"
    ) {
      val root =
        string2dir(
          s"""
            |/scalafmt.conf
            |style = default
            |version="$version"
            |/scalafile.scala
            |$unformatted
            |/scalatex.scalatex
            |$unformatted
            |/sbt.sbt
            |$sbtOriginal
            |/sbt.sbtfile
            |$sbtOriginal""".stripMargin
        )

      val config = root / "scalafmt.conf"
      val args = mkArgs(s"--config $config")
      val opts = getMockOptions(root)

      val conf = Cli.getConfig(args, opts)
      Cli.run(conf.get)

      assertNoDiff(dir2string(root / "scalatex.scalatex"), unformatted)
      assertNoDiff(dir2string(root / "sbt.sbtfile"), sbtOriginal)

      assertNoDiff(dir2string(root / "scalafile.scala"), formatted)
      val sbtFormatted =
        """|lazy val x = project
          |lazy val y = project
          |""".stripMargin
      assertNoDiff(dir2string(root / "sbt.sbt"), sbtFormatted)
    }

    test(
      s"includeFilters are ignored for full paths but NOT test for passed directories: $label"
    ) {
      val root =
        string2dir(
          s"""
            |/inner1/file1.scala
            |$unformatted
            |/inner2/file2.scalahala
            |$unformatted
            |/inner3/file1.scala
            |$unformatted
            |/inner3/file2.scalahala
            |$unformatted""".stripMargin
        )
      val inner1 = root / "inner1"
      val inner2 = root / "inner2"
      val inner3 = root / "inner3"
      val full1 = inner3 / "file1.scala"
      val full2 = inner3 / "file2.scalahala"

      val opts = Seq(
        s"""--config-str {version="$version"}"""
      ) ++ Seq(inner1, inner2, full1, full2)
      runWith(root, opts.mkString(" "))

      assertNoDiff(dir2string(inner1 / "file1.scala"), formatted)
      assertNoDiff(dir2string(inner2 / "file2.scalahala"), unformatted)
      assertNoDiff(dir2string(full1), formatted)
      assertNoDiff(dir2string(full2), formatted)
    }

    test(
      s"includeFilters are respected for full paths but NOT test for passed directories: $label"
    ) {
      val root =
        string2dir(
          s"""
            |/inner1/file1.scala
            |$unformatted
            |/inner2/file2.scalahala
            |$unformatted
            |/inner3/file1.scala
            |$unformatted
            |/inner3/file2.scalahala
            |$unformatted""".stripMargin
        )
      val inner1 = root / "inner1"
      val inner2 = root / "inner2"
      val inner3 = root / "inner3"
      val full1 = inner3 / "file1.scala"
      val full2 = inner3 / "file2.scalahala"

      val opts = Seq(
        "--respect-project-filters",
        s"""--config-str {version="$version"}"""
      ) ++ Seq(inner1, inner2, full1, full2)
      runWith(root, opts.mkString(" "))

      assertNoDiff(dir2string(inner1 / "file1.scala"), formatted)
      assertNoDiff(dir2string(inner2 / "file2.scalahala"), unformatted)
      assertNoDiff(dir2string(full1), formatted)
      assertNoDiff(dir2string(full2), unformatted)
    }

    test(s"--config accepts absolute paths: $label") {
      val root = string2dir(
        s"""/scalafmt.conf
          |version = "$version"
          |style = intellij
          |/foo.scala
          |object    A
      """.stripMargin
      )
      val config = (root / "scalafmt.conf").path
      val toFormat = (root / "foo.scala").path
      val args = Array[String](
        "--config",
        config,
        toFormat
      )
      Cli.exceptionThrowingMainWithOptions(args, baseCliOptions)
      val obtained = FileOps.readFile(toFormat)
      assertNoDiff(obtained, "object A\n")
    }

    // These are tests for deprecated flags
    test(s"scalafmt -i -f file1,file2,file3 should still work: $label") {
      val file1 = Files.createTempFile("prefix", ".scala")
      val file2 = Files.createTempFile("prefix2", ".scala")
      val file3 = Files.createTempFile("prefix3", ".scala")
      Files.write(file1, unformatted.getBytes)
      Files.write(file2, unformatted.getBytes)
      Files.write(file3, unformatted.getBytes)
      def fileStr(fs: Path*) = fs.map(_.toFile.getPath).mkString(",")
      val args = Array(
        "--config-str",
        s"""{version="$version",style=IntelliJ}""",
        "-i",
        "-f",
        fileStr(file1, file2, file3)
      )
      val formatInPlace = getConfig(args)
      Cli.run(formatInPlace)
      val obtained = FileOps.readFile(file1.toString)
      val obtained2 = FileOps.readFile(file2.toString)
      val obtained3 = FileOps.readFile(file3.toString)
      assertNoDiff(obtained, formatted)
      assertNoDiff(obtained2, formatted)
      assertNoDiff(obtained3, formatted)
    }

    test(s"parse error is formatted nicely: $label") {
      val input =
        """|/foo.scala
          |object    A { foo( }
          |""".stripMargin
      noArgTest(
        string2dir(input),
        input,
        Seq(
          Array(
            "--config-str",
            s"""{version="$version",style=IntelliJ}"""
          )
        ),
        assertExit = { exit => assert(exit.is(ExitCode.ParseError)) },
        assertOut = out => {
          assertContains(
            out,
            s"""foo.scala:1: error:$dialectError illegal start of simple expression
              |object    A { foo( }
              |                   ^""".stripMargin
          )
        }
      )
    }

    test(s"command line argument error: $label") {
      val exit = Console.withErr(NoopOutputStream.printStream) {
        Cli.mainWithOptions(
          Array("--foobar"),
          getMockOptions(AbsoluteFile.userDir)
        )
      }
      assert(exit.is(ExitCode.CommandLineArgumentError), exit)
    }

    test(s"--test failure prints out unified diff: $label") {
      val fooFile = "foo.scala"
      val input =
        s"""|/.scalafmt.conf
          |onTestFailure = "To fix this ..."
          |version = "$version"
          |
          |/$fooFile
          |object    A { }
          |""".stripMargin
      val dir = string2dir(input)
      val fooPath = dir / fooFile
      noArgTest(
        dir,
        input,
        Seq(Array("--test")),
        assertExit = { exit => assert(exit.is(ExitCode.TestError)) },
        assertOut = out => {
          assertContains(
            out,
            s"""b$fooPath
              |@@ -1,1 +1,1 @@
              |-object    A { }
              |+object A {}
              |error: --test failed
              |To fix this ...""".stripMargin
          )
        }
      )
    }

    test(s"--test succeeds even with parse error: $label") {
      val input =
        """|/foo.scala
          |object A {
          |""".stripMargin
      noArgTest(
        string2dir(input),
        input,
        Seq(Array("--test", "--config-str", s"""{version="$version"}""")),
        assertExit = { exit => assert(exit.isOk) },
        assertOut = out => {
          assert(
            out.contains(
              s"foo.scala:2: error:$dialectError } expected but end of file found"
            ) &&
              out.contains(
                "error: ParseError=2"
              )
          )
        }
      )
    }

    test(s"--test fails with parse error if fatalWarnings=true: $label") {
      val input =
        s"""|/.scalafmt.conf
          |runner.fatalWarnings = true
          |version = "$version"
          |/foo.scala
          |object A {
          |""".stripMargin
      noArgTest(
        string2dir(input),
        input,
        Seq(Array("--test")),
        assertExit = { exit => assert(exit == ExitCode.ParseError) },
        assertOut = out => {
          assert(
            out.contains(
              s"foo.scala:2: error:$dialectError } expected but end of file found"
            ) && out.contains(
              "error: ParseError=2"
            )
          )
        }
      )
    }

    test(s"exception is thrown on invalid .scalafmt.conf: $label") {
      val input =
        s"""/.scalafmt.conf
          |version="$version"
          |blah = intellij
          |/foo.scala
          |object A {}
      """.stripMargin
      noArgTest(
        string2dir(input),
        input,
        Seq(Array.empty),
        assertExit = { exit => assert(exit == ExitCode.UnexpectedError) },
        assertOut = out => {
          assert(
            out.contains("Invalid field: blah") ||
              out.contains("found option 'blah' which wasn't expected"),
            s"assertion failed [$out]"
          )
        }
      )
    }

    test(s"eof: $label") {
      val in = Files.createTempFile("scalafmt", "Foo.scala")
      Files.write(in, "object A".getBytes(StandardCharsets.UTF_8))
      val exit = Cli.mainWithOptions(Array(in.toString), baseCliOptions)
      assert(exit.isOk)
      val obtained = new String(Files.readAllBytes(in), StandardCharsets.UTF_8)
      assert(obtained == "object A\n")
    }

    test(s"--config-str should be used if it is specified: $label") {
      val expected = "This message should be shown"
      val unexpected = "This message should not be shown"
      val input =
        s"""|/.scalafmt.conf
          |onTestFailure = "$unexpected"
          |version = "$version"
          |
          |/foo.scala
          |object      A { }
          |""".stripMargin
      noArgTest(
        string2dir(input),
        input,
        Seq(
          Array(
            "--config-str",
            s"""{version="$version",onTestFailure="$expected"}""",
            "--test"
          )
        ),
        assertExit = { exit => assert(exit.is(ExitCode.TestError)) },
        assertOut = out => {
          assert(
            out.contains(expected) &&
              !out.contains(unexpected)
          )
        }
      )
    }

    test(
      s"--list enable scalafmt to output a list of unformatted files with ExitCode.TestError: $label"
    ) {
      val input =
        s"""|/.scalafmt.conf
          |version = "$version"
          |
          |/bar.scala
          |object    A { }
          |
          |/baz.scala
          |object A {}
          |
          |/dir/foo.scala
          |object   A { }
          |""".stripMargin
      val dir = string2dir(input)
      noArgTest(
        dir,
        input,
        Seq(Array("--list")),
        assertExit = { exit => assert(exit.is(ExitCode.TestError)) },
        assertOut = out => {
          assert(
            out.contains("bar.scala") &&
              !out.contains("baz.scala") &&
              out.contains("dir/foo.scala")
          )
        }
      )
    }
  }
}

class CliTest extends AbstractCliTest with CliTestBehavior {
  testCli("1.6.0-RC4") // test for runDynamic
  testCli(Versions.version) // test for runScalafmt

  test(
    "Running pre-resolved version of scalafmt if .scalafmt.conf is missing."
  ) {
    val input =
      s"""|/foo.scala
        |object A {}
        |""".stripMargin
    noArgTest(
      string2dir(input),
      input,
      Seq(
        Array(
          "--debug"
        ) // debug options is needed to output running scalafmt version
      ),
      assertExit = { exit => assert(exit.isOk, exit) },
      assertOut = out => {
        assert(out.contains(Versions.version))
      }
    )
  }

  test(
    "Running pre-resolved version of scalafmt if `version` setting is missing."
  ) {
    val input =
      s"""|/.scalafmt.conf
        |maxColumn = 10
        |
        |/foo.scala
        |object A {}
        |""".stripMargin
    noArgTest(
      string2dir(input),
      input,
      Seq(
        Array(
          "--debug"
        ) // debug options is needed to output running scalafmt version
      ),
      assertExit = { exit => assert(exit.isOk, exit) },
      assertOut = out => {
        assert(out.contains(Versions.version))
      }
    )
  }

  test("arguments starting with @ are expanded from a file") {
    val argumentsFile = Files.createTempFile("scalafmt", "arguments")
    val configFile = Files.createTempFile("scalafmt", ".scalafmt.conf")
    val arguments = List("--config", configFile.toString(), "foobar.scala")
    Files.write(argumentsFile, arguments.asJava)
    Files.write(configFile, List("maxColumn=40").asJava)
    val obtained =
      Cli.getConfig(Array(s"@$argumentsFile"), CliTest.defaultOptions).get
    val config = obtained.scalafmtConfig.get
    assert(config.maxColumn == 40)
    assert(obtained.files.head.jfile.getName() == "foobar.scala")
  }

  test("can't specify both --config and --config-str") {
    val errStream = new ByteArrayOutputStream()
    val obtained = Console.withErr(errStream) {
      Cli.getConfig(
        Array("--config", "foo", "--config-str", "bar"),
        CliTest.defaultOptions
      )
    }
    assertEquals(
      errStream.toString.trim,
      "Error: may not specify both --config and --config-str"
    )
    assertEquals(None: Option[CliOptions], obtained)
  }

  test(s"scalafmt use includePaths") {
    val input = string2dir(
      s"""|/bar.scala
        |object    FormatMe {
        |  val x = 1
        |}
        |
        |/target/foo.scala
        |object A   { }
        |
        |/.scalafmt.conf
        |maxColumn = 2
        |project { includePaths = ["glob:**/bar.scala"] }
        |""".stripMargin
    )

    val expected =
      s"""|/.scalafmt.conf
        |maxColumn = 2
        |project { includePaths = ["glob:**/bar.scala"] }
        |
        |/bar.scala
        |object FormatMe {
        |  val x =
        |    1
        |}
        |
        |/target/foo.scala
        |object A   { }
        |""".stripMargin
    noArgTest(
      input,
      expected,
      Seq(Array.empty[String], Array("--mode", "diff"))
    )
  }

  test(s"scalafmt use excludePaths") {
    val input = string2dir(
      s"""|/foo.scala
        |object    FormatMe {
        |  val x = 1
        |}
        |
        |/target/foo.scala
        |object A   { }
        |
        |/.scalafmt.conf
        |maxColumn = 2
        |project { excludePaths = ["glob:**target**"] }
        |""".stripMargin
    )

    val expected =
      s"""|/.scalafmt.conf
        |maxColumn = 2
        |project { excludePaths = ["glob:**target**"] }
        |
        |/foo.scala
        |object FormatMe {
        |  val x =
        |    1
        |}
        |
        |/target/foo.scala
        |object A   { }
        |""".stripMargin
    noArgTest(
      input,
      expected,
      Seq(Array.empty[String], Array("--mode", "diff"))
    )
  }

  test(s"scalafmt: includeFilters overrides default includePaths") {
    val input = string2dir(
      s"""|/bar.scala
        |object    FormatMe {
        |  val x = 1
        |}
        |
        |/target/foo.scala
        |object A   { }
        |
        |/.scalafmt.conf
        |maxColumn = 2
        |project { includeFilters = ["bar"] }
        |""".stripMargin
    )

    val expected =
      s"""|/.scalafmt.conf
        |maxColumn = 2
        |project { includeFilters = ["bar"] }
        |
        |/bar.scala
        |object FormatMe {
        |  val x =
        |    1
        |}
        |
        |/target/foo.scala
        |object A   { }
        |""".stripMargin
    noArgTest(
      input,
      expected,
      Seq(Array.empty[String], Array("--mode", "diff"))
    )
  }

  test(s"scalafmt: includeFilters with explicit includePaths") {
    val defaultIncludePathsJson =
      ProjectFiles.defaultIncludePaths.mkString("[\"", "\", \"", "\"]")
    val input = string2dir(
      s"""|/bar.scala
        |object    FormatMe {
        |  val x = 1
        |}
        |
        |/target/foo.scala
        |object A   { }
        |
        |/.scalafmt.conf
        |maxColumn = 2
        |project {
        |  includePaths = $defaultIncludePathsJson
        |  includeFilters = ["bar"]
        |}
        |""".stripMargin
    )

    val expected =
      s"""|/.scalafmt.conf
        |maxColumn = 2
        |project {
        |  includePaths = $defaultIncludePathsJson
        |  includeFilters = ["bar"]
        |}
        |
        |/bar.scala
        |object FormatMe {
        |  val x =
        |    1
        |}
        |
        |/target/foo.scala
        |object A {}
        |""".stripMargin
    noArgTest(
      input,
      expected,
      Seq(Array.empty[String], Array("--mode", "diff"))
    )
  }

  test(s"handles .md files when present in includePaths") {
    val input = string2dir(
      s"""|/foobar.md
        |# Hello
        |Example usage 1 with long   spaced line
        |```scala mdoc
        |val  x   =   42
        |```
        |Example usage 2
        |```java
        |val  x   =   42
        |```
        |/.scalafmt.conf
        |maxColumn   = 8
        |project.includePaths."+" = ["glob:**.md"]
        |""".stripMargin
    )
    val expected =
      s"""|
        |/.scalafmt.conf
        |maxColumn   = 8
        |project.includePaths."+" = ["glob:**.md"]
        |
        |/foobar.md
        |# Hello
        |Example usage 1 with long   spaced line
        |```scala mdoc
        |val x =
        |  42
        |```
        |Example usage 2
        |```java
        |val  x   =   42
        |```
        |""".stripMargin

    noArgTest(
      input,
      expected,
      Seq(Array.empty[String], Array("--mode", "diff"))
    )
  }

  test(s"ignores .md files if not present in includePaths") {
    val input = string2dir(
      s"""|/foobar.md
        |# Hello
        |Example usage 1
        |```scala mdoc
        |val  x   =   42
        |```
        |/.scalafmt.conf
        |""".stripMargin
    )
    val expected =
      s"""|
        |/.scalafmt.conf
        |
        |/foobar.md
        |# Hello
        |Example usage 1
        |```scala mdoc
        |val x   =   42
        |```
        |""".stripMargin

    try {
      noArgTest(
        input,
        expected,
        Seq(Array.empty[String], Array("--mode", "diff"))
      )
      fail(
        "Should have thrown noMatchingFiles because our markdown file was skipped"
      )
    } catch {
      case _: org.scalafmt.Error.NoMatchingFiles.type => ()
    }
  }

  test(s"handles .md with normal comment that contains a nested fence") {
    val input = string2dir(
      s"""|/foobar.md
        | Intro
        |```scala mdoc
        |object    A {
        | /*
        |  * ```scala mdoc
        |  *    val example = "text"
        |  * ```
        |  */
        |                       }
        |```
        |""".stripMargin
    )
    val expected =
      s"""|/foobar.md
        | Intro
        |```scala mdoc
        |object A {
        |  /*
        |   * ```scala mdoc
        |   *    val example = "text"
        |   * ```
        |   */
        |}
        |```
        |""".stripMargin
    val options = getConfig(
      Array(
        input.path,
        "--config-str",
        s"""{project.includePaths."+" = ["glob:**.md"]}"""
      )
    )
    Cli.run(options)
    val obtained = dir2string(input)
    assertNoDiff(obtained, expected)
  }

  // This test might need to change based on maintainer feedback/requirements
  test(s"does not apply to .md files with indented fenced content ") {
    val input = string2dir(
      s"""|/foobar2.md
        | Intro text:
        |  ```scala mdoc
        |        object    A {      }
        |  ```
        |""".stripMargin
    )
    val expected =
      s"""|/foobar2.md
        | Intro text:
        |  ```scala mdoc
        |        object    A {      }
        |  ```
        |""".stripMargin
    val options = getConfig(
      Array(
        input.path,
        "--config-str",
        s"""{project.includePaths."+" = ["glob:**.md"]}"""
      )
    )
    Cli.run(options)
    val obtained = dir2string(input)
    assertNoDiff(obtained, expected)
  }

  // This test might need to change based on maintainer feedback/requirements
  test(s"does not format nested fences when not inside a Scala comment") {
    val input = string2dir(
      s"""|/foobar.md
        |```scala mdoc
        |object    A {
        | /*
        |```scala mdoc
        |   val example = "text"
        |```
        |  */
        |  }
        |```
        |""".stripMargin
    )
    val expected =
      s"""|/foobar.md
        |```scala mdoc
        |object    A {
        | /*
        |```scala mdoc
        |   val example = "text"
        |```
        |  */
        |  }
        |```
        |""".stripMargin
    val options = getConfig(
      Array(
        input.path,
        "--config-str",
        s"""{project.includePaths."+" = ["glob:**.md"]}"""
      )
    )
    Cli.run(options)
    val obtained = dir2string(input)
    assertNoDiff(obtained, expected)
  }

  test(s"handles .md fences with uneven backticks") {
    val input = string2dir(
      s"""|/foobar.md
        |# Hello
        |Example usage 1 with long   spaced line
        |```scala mdoc
        |val  x   =   42
        |`````
        |Example usage 2
        |```java
        |val  x   =   42
        |```
        |/.scalafmt.conf
        |maxColumn   = 8
        |project.includePaths."+" = ["glob:**.md"]
        |""".stripMargin
    )
    val expected =
      s"""|
        |/.scalafmt.conf
        |maxColumn   = 8
        |project.includePaths."+" = ["glob:**.md"]
        |
        |/foobar.md
        |# Hello
        |Example usage 1 with long   spaced line
        |```scala mdoc
        |val x =
        |  42
        |`````
        |Example usage 2
        |```java
        |val  x   =   42
        |```
        |""".stripMargin

    noArgTest(
      input,
      expected,
      Seq(Array.empty[String], Array("--mode", "diff"))
    )
  }

  test("no final EOL") {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val codeNoEol =
      """|object FormatMe {
        |  val x =
        |    1
        |}""".stripMargin
    val res = Console.withOut(out) {
      Console.withErr(err) {
        val options = getConfig(Array("--stdin", "--test"))
        val options2 = options.copy(
          common = options.common.copy(
            in = new ByteArrayInputStream(codeNoEol.getBytes),
            out = Console.out,
            err = Console.err
          )
        )
        Cli.run(options2)
      }
    }
    assertEquals(res.code, 1)
    assertEquals(
      CliTest.stripCR(out.toString),
      "error: --test failed\n"
    )
    assertEquals(
      CliTest.stripCR(err.toString),
      """--- astdin.scala
        |+++ bstdin.scala
        |@@ -4,1 +4,2 @@
        | }
        |+
        |""".stripMargin
    )
  }

}

object CliTest {

  val stripCR: String => String = {
    val eol = System.lineSeparator()
    if (eol == "\n") identity else _.replace(eol, "\n")
  }

  val defaultOptions =
    CliOptions.default.copy(baseConfig = ScalafmtConfig.default)

}
