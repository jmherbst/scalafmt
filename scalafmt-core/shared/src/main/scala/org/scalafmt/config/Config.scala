package org.scalafmt.config

import java.io.File

import metaconfig._
import org.scalafmt.config.PlatformConfig._

// NOTE: these methods are intended for internal usage and are subject to
// binary and source breaking changes between any release. For a stable API
// use org.scalafmt.Scalafmt. Documentation on using scalafmt as a library
// can be seen here https://scalameta.org/scalafmt/#Standalonelibrary
object Config {

  def hoconStringToConf(input: String, path: Option[String]): Configured[Conf] =
    Input.String(input).parse(path)

  def hoconFileToConf(input: File, path: Option[String]): Configured[Conf] =
    Configured
      .fromExceptionThrowing(Input.File(input))
      .andThen(_.parse(path))

  def fromHoconString(
      string: String,
      default: ScalafmtConfig = ScalafmtConfig.default,
      path: Option[String] = None
  ): Configured[ScalafmtConfig] =
    fromConf(hoconStringToConf(string, path), default = default)

  /** Read ScalafmtConfig from String contents from an optional HOCON path. */
  def fromHoconFile(
      file: File,
      default: ScalafmtConfig = ScalafmtConfig.default,
      path: Option[String] = None
  ): Configured[ScalafmtConfig] =
    fromConf(hoconFileToConf(file, path), default = default)

  def fromConf(
      conf: Configured[Conf],
      default: ScalafmtConfig,
      path: Option[String] = None
  ): Configured[ScalafmtConfig] =
    conf.andThen { baseConf =>
      val next = path match {
        case None => Configured.Ok(baseConf)
        case Some(p) =>
          baseConf match {
            case Conf.Obj(values) =>
              values
                .collectFirst { case (`p`, value) => Configured.Ok(value) }
                .getOrElse(
                  ConfError.message(s"Config $baseConf has no field $p").notOk
                )
            case x =>
              ConfError.typeMismatch("Conf.Obj", x).notOk
          }
      }
      val decoded = ScalafmtConfig.decoder.read(Option(default), next)
      decoded match {
        case Configured.Ok(x)
            if default.eq(ScalafmtConfig.uncheckedDefault) && path.isEmpty =>
          x.runner.warnDefault
        case _ =>
      }
      decoded
    }

}
