import cats.data.Ior
import cats.effect.{Blocker, ContextShift, IO}
import cats.implicits.catsSyntaxIorId
import com.zaxxer.hikari.HikariDataSource
import doobie.Meta
import doobie.hikari.HikariTransactor
import edu.gemini.grackle.Cursor.Env
import edu.gemini.grackle.Path.UniquePath
import edu.gemini.grackle.Predicate._
import edu.gemini.grackle.Query._
import edu.gemini.grackle.QueryCompiler.SelectElaborator
import edu.gemini.grackle.Value.StringValue
import edu.gemini.grackle._
import edu.gemini.grackle.doobie.{DoobieMapping, DoobieMonitor}
import edu.gemini.grackle.syntax.StringContextOps

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

case class Inner(inner: String)

trait TestMapping[F[_]] extends DoobieMapping[F] {
  override val schema: Schema =
    schema"""
          type Query {
            test(string: String!): [Outer!]!
          }

          scalar Long
          scalar StringValue

          type Outer {
            id: Long!
            value: String!
            join: String!
            inner: Inner!
          }

          type Inner {
            inner: String!
            join: String!
          }
        """

  val QueryType: TypeRef = schema.ref("Query")
  val OuterType: TypeRef = schema.ref("Outer")
  val InnerType: TypeRef = schema.ref("Inner")
  val LongType: TypeRef = schema.ref("Long")

  object outer extends TableDef("outer") {
    val id = col("id", Meta[Long])
    val value = col("value", Meta[String])
    val join = col("join", Meta[String])
  }

  object inner extends TableDef("inner") {
    val id = col("id", Meta[Long])
    val value = col("value", Meta[String])
    val join = col("join", Meta[String])
  }

  override val typeMappings: List[TypeMapping] =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings = List(
          SqlRoot("test")
        )
      ),
      PrefixedMapping(
        tpe = OuterType,
        mappings = List(
          List("test") -> ObjectMapping(
            tpe = OuterType,
            fieldMappings = List(
              SqlField("id", outer.id , key = true),
              SqlField("value", outer.value),
              SqlField("join", outer.join, key = true),
              SqlObject("inner", Join(outer.id, inner.id), Join(outer.join, inner.join))
            )
          )
        )
      ),
      ObjectMapping(
        tpe = InnerType,
        fieldMappings = List(
          SqlField("id", inner.id , key = true, hidden = true),
          SqlField("join", inner.join, key = true, hidden = true),
          SqlField("inner", inner.value),
        )
      ),
      LeafMapping[Long](LongType)
    )

  object StringValue {
    def unapply(s: StringValue): Option[String] = Some(s.value)
  }

  override val selectElaborator: SelectElaborator = new SelectElaborator(
    Map(
      QueryType -> {
        case Select("test", List(Binding("string", StringValue(str))), child) =>
          Environment(
            Env("string" -> str),
            Select(
              "test",
              Nil,
              Filter(Eql(UniquePath[String](List("value")), Const(str)), child)
            )
          ).rightIor
      }
    )
  )

  def debug(query: Query): MappedQuery = MappedQuery.apply(query, Nil, QueryType)

  def renderQuery(query: String): Fragment = {
    val Ior.Right(q) = compiler.compile(query)
    val s = debug(q.query)
    s.fragment
  }
}

object Main extends App {
  val query: String =
    """
       {
          test(string: "a") {
            id
            value
            join
            inner {
              inner
            }
          }
       }
    """

  val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val cs: ContextShift[IO] = IO.contextShift(ec)
  val blocker: Blocker = Blocker.liftExecutionContext(ec)

  lazy val transactor = {
    val ds = new HikariDataSource
    HikariTransactor[IO](ds, ec, blocker)
  }

  val mapping: TestMapping[IO] = new DoobieMapping[IO](transactor, DoobieMonitor.noopMonitor) with TestMapping[IO]

  println(mapping.renderQuery(query))
  // Expect the fragment to contain:
  //   - LEFT JOIN inner ON outer.join inner.join
  //   - WHERE ( outer.value = ? ) AND ( outer.id = inner.id )
}
