package retier
package impl
package engine.generators

import engine._
import scala.reflect.macros.blackbox.Context

trait PeerDefinitionProcessor { this: Generation =>
  val c: Context
  import c.universe._

  val processPeerDefinitions = AugmentedAggregation[
    PeerDefinition, NonPlacedStatement] {
      aggregator =>

    val synthetic = Flag.SYNTHETIC
    val peerTypeTag = TermName(names.peerTypeTag)

    def wildcardedTypeTree(expr: Tree, typeArgsCount: Int) =
      if (typeArgsCount == 0)
        expr
      else {
        val wildcards = ((0 until typeArgsCount) map { _ =>
          TypeName(c freshName "_")
        }).toList

        ExistentialTypeTree(
          AppliedTypeTree(expr, wildcards map { Ident(_) }),
          wildcards map { TypeDef(
            Modifiers(Flag.DEFERRED | Flag.SYNTHETIC), _, List.empty,
            TypeBoundsTree(EmptyTree, EmptyTree))
          })
      }

    def createImplicitPeerTypeTag(peerDefinition: PeerDefinition) = {
      import trees._

      val PeerDefinition(_, peerName, _, typeArgs, _, parents, _, _, _, _) =
        peerDefinition

      val name = peerName.toString
      val wildcardedPeerType = wildcardedTypeTree(tq"$peerName", typeArgs.size)

      val bases = parents collect {
        case parent if parent.tpe =:= types.peer =>
          q"$PeerType"

        case parent @ tq"$tpname.this.$tpnamePeer[..$_]"
            if parent.tpe <:< types.peer =>
          val name = tpnamePeer.toTermName
          q"$tpname.this.$name.$peerTypeTag.peerType"

        case parent if parent.tpe <:< types.peer =>
          c.abort(parent.pos, "peer type of same scope expected")
      }

      q"""$synthetic implicit val $peerTypeTag =
            $PeerTypeTagCreate[$wildcardedPeerType]($name, $List(..$bases))"""
    }

    def processPeerCompanion(peerDefinition: PeerDefinition) = {
      val PeerDefinition(_, peerName, _, _, _, _, _, _, _, companion) =
        peerDefinition

      val companionName = peerName.toTermName
      val implicitPeerTypeTag = createImplicitPeerTypeTag(peerDefinition)

      companion match {
        case Some(q"""$mods object $tname
                    extends { ..$earlydefns } with ..$parents { $self =>
                    ..$stats
                  }""") =>
          stats foreach {
            case stat: DefTree if stat.name == peerTypeTag =>
              c.abort(stat.pos,
                "member of name `peerTypeTag` not allowed " +
                "in peer type companion objects")
            case _ =>
          }

          parents foreach { parent =>
            if ((parent.tpe member peerTypeTag) != NoSymbol)
              c.abort(parent.pos,
                "member of name `peerTypeTag` not allowed " +
                "in peer type companion object parents")
          }

          q"""$mods object $tname
            extends { ..$earlydefns } with ..$parents { $self =>
            $implicitPeerTypeTag
            ..$stats
          }"""

        case _ =>
          q"object $companionName { $implicitPeerTypeTag }"
      }
    }

    def processPeerDefinition(peerDefinition: PeerDefinition) = {
      import trees._

      val PeerDefinition(_, peerName, _, typeArgs, args, parents, mods, stats,
        isClass, _) = peerDefinition

      val companionName = peerName.toTermName
      val implicitPeerTypeTag = q"""$synthetic implicit val $peerTypeTag =
        $companionName.$peerTypeTag.asInstanceOf[$PeerTypeTag[this.type]]"""

      if (isClass)
        q"""$mods class $peerName[..$typeArgs](...$args) extends ..$parents {
          $implicitPeerTypeTag
          ..$stats
        }"""
      else
        q"""$mods trait $peerName[..$typeArgs] extends ..$parents {
          $implicitPeerTypeTag
          ..$stats
        }"""
    }

    val definitions = aggregator.all[PeerDefinition] flatMap { peerDefinition =>
      Seq(
        processPeerDefinition(peerDefinition),
        processPeerCompanion(peerDefinition))
    }

    echo(
      verbose = true,
      s"Processed peer definitions " +
      s"(${definitions.size} non-placed statements added)")

    aggregator add (definitions map NonPlacedStatement)
  }
}
