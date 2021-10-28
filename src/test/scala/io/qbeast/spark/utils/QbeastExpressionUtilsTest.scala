package io.qbeast.spark.utils

import io.qbeast.spark.QbeastIntegrationTestSpec
import io.qbeast.spark.index.Weight
import io.qbeast.spark.model.{LinearTransformation, Point, QuerySpaceFromTo, Revision}
import io.qbeast.spark.sql.rules.QbeastMurmur3Hash
import io.qbeast.spark.sql.utils.QbeastExpressionUtils
import org.apache.spark.sql.Column
import org.apache.spark.sql.catalyst.expressions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class QbeastExpressionUtilsTest extends AnyFlatSpec with Matchers with QbeastIntegrationTestSpec {

  private def weightFilters(from: Weight, to: Weight): Expression = {
    val qbeast_hash = new QbeastMurmur3Hash(Seq(new Column("id").expr))
    val lessThan = LessThan(qbeast_hash, Literal(to.value))
    val greaterThanOrEqual = GreaterThanOrEqual(qbeast_hash, Literal(from.value))
    And(lessThan, greaterThanOrEqual)
  }

  private def rangeFilters(from: Any, to: Any, columnName: String): Expression = {

    val column = new Column(columnName).expr
    val lessThan = LessThan(column, Literal(to))
    val greaterThanOrEqual = GreaterThanOrEqual(column, Literal(from))
    And(lessThan, greaterThanOrEqual)
  }

  private def createRevision(columnNames: Seq[String]) = {
    val transformations = columnNames.map(s => LinearTransformation(0.0, 1.0)).toIndexedSeq
    new Revision(
      System.currentTimeMillis(),
      System.currentTimeMillis(),
      10000,
      columnNames,
      transformations)
  }

  "extractWeightRange" should
    "extract all weight range when expressions is empty" in withSpark(spark => {

      QbeastExpressionUtils
        .extractWeightRange(Seq.empty) shouldBe ((Weight.MinValue, Weight.MaxValue))
    })

  it should "filter correctly the expressions for weight" in withSpark(_ => {

    val (from, to) = (Weight(3), Weight(8))
    val expression = weightFilters(from, to)
    QbeastExpressionUtils.extractWeightRange(Seq(expression)) shouldBe ((from, to))

  })

  "extractQueryRange" should "extract query range" in withSpark(spark => {

    val (from, to) = (3, 8)
    val columnName = "id"
    val expression = rangeFilters(from, to, columnName)
    val revision = createRevision(Seq(columnName))
    QbeastExpressionUtils
      .extractQuerySpace(Seq(expression), revision, spark) shouldBe
      QuerySpaceFromTo(Point(from), Point(to), revision)

  })

  it should "extract all query range when expressions is empty" in withSpark(spark => {

    val revision = createRevision(Seq("id"))
    QbeastExpressionUtils
      .extractQuerySpace(Seq.empty, revision, spark) shouldBe
      QuerySpaceFromTo(Point(Int.MinValue), Point(Int.MaxValue), revision)
  })

  it should "not process disjunctive predicates" in withSpark(spark => {

    val (from, to) = (3, 8)
    val columnName = "id"
    val revision = createRevision(Seq(columnName))

    val expression =
      Or(
        LessThan(new Column(columnName).expr, Literal(from)),
        GreaterThanOrEqual(new Column(columnName).expr, Literal(to))).canonicalized

    QbeastExpressionUtils
      .extractQuerySpace(Seq(expression), revision, spark) shouldBe
      QuerySpaceFromTo(Point(Int.MinValue), Point(Int.MaxValue), revision)

  })

  "extractDataFilters" should "extract qbeast filters correctly" in withSpark(spark => {

    val (from, to) = (3, 8)
    val columnName = "id"
    val revision = createRevision(Seq(columnName))
    val rangeExpression = rangeFilters(from, to, columnName)
    val weightExpression = weightFilters(Weight(from), Weight(to))
    val expressions = Seq(rangeExpression, weightExpression)

    QbeastExpressionUtils
      .extractDataFilters(expressions, revision, spark) shouldBe expressions

  })
}
