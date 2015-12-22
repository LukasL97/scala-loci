package retier

import scala.annotation.compileTimeOnly
import scala.annotation.StaticAnnotation
import scala.language.experimental.macros

@compileTimeOnly("enable macro paradise to expand macro annotations")
class multitier extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any =
    macro impl.engine.multitier.annotation
}

object multitier {
  @throws[RemoteConnectionException](
    "if the connection setup does not respect the connection specification")
  def run[P <: Peer]: Runtime =
    macro impl.engine.multitier.run[P]

  @compileTimeOnly("only usable in `multitier` environment")
  def terminate(): Unit = `#macro`
}
