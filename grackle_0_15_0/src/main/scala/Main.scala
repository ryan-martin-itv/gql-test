import cats.effect._
import com.zaxxer.hikari.HikariDataSource
import doobie.Meta
import doobie.hikari.HikariTransactor
import grackle.PathTerm.UniquePath
import grackle.Predicate._
import grackle.Query._
import grackle.QueryCompiler.{Elab, SelectElaborator}
import grackle.Value.StringValue
import grackle._
import grackle.doobie.postgres.{DoobieMapping, DoobieMonitor}
import grackle.syntax.toStringContextOps

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

trait TestMapping[F[_]] extends DoobieMapping[F] {
  override val schema: Schema =
    schema"""
          type Query {
            test(string: String!): [Outer!]
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
          SqlObject("test")
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
          SqlField("inner", inner.value)
        )
      ),
      LeafMapping[Long](LongType)
    )

  object StringValue {
    def unapply(s: StringValue): Option[String] = Some(s.value)
  }

  override val selectElaborator: SelectElaborator = SelectElaborator {
    case (
      QueryType,
      "test",
      List(Binding("string", StringValue(str)))
    ) =>
      Elab.transformChild { child =>
        Filter(
          Eql(
            OuterType / "value", Const(str)
          ),
          child
        )
      }
  }

  def debug(query: Query): Result[MappedQuery] = MappedQuery.apply(query, Context(QueryType))

  def renderQuery(query: String): Fragment = {
    val Result.Success(q) = compiler.compile(query)
    val Result.Success(s) = debug(q.query)
    val Result.Success(f) = s.fragment
    f
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

  lazy val transactor = {
    val ds = new HikariDataSource
    HikariTransactor[IO](ds, ec)
  }

  val mapping: TestMapping[IO] = new DoobieMapping[IO](transactor, DoobieMonitor.noopMonitor) with TestMapping[IO]

  println(mapping.renderQuery(query))
  /* Expect the fragment to contain:
       - LEFT JOIN inner ON outer.join inner.join
       - WHERE ( outer.value = ? ) AND ( outer.id = inner.id )
     Result:
      SELECT inner_outer_nested.id,
             inner_outer_nested.join,
             inner_outer_nested.value,
             outer.id    AS id_alias_0,
             outer.join  AS join_alias_1,
             outer.value AS value_alias_2
      FROM outer
               INNER JOIN outer ON (outer.id = outer.id)
               INNER JOIN LATERAL (SELECT inner.join, inner.id, inner.value
                                   FROM inner
                                            INNER JOIN outer ON (outer.join = inner.join) ) AS inner_outer_nested
                          ON (inner_outer_nested.join = outer.join)
      WHERE (outer.value = ?)
   */
}