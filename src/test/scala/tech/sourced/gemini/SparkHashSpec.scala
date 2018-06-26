package tech.sourced.gemini

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import org.slf4j.{Logger => Slf4jLogger}
import tech.sourced.gemini.cmd.HashSparkApp

@tags.Bblfsh
@tags.FeatureExtractor
@tags.Spark
class SparkHashSpec extends FlatSpec
  with Matchers
  with BaseSparkSpec
  with Logging
  with BeforeAndAfterAll {

  useDefaultSparkConf()

  // files for hash test
  val filePaths = Array(
    // should be ignored for similarity
    ".gitignore",
    // should be processed
    "archiver.go",
    "archiver_test.go"
  )

  // don't process content of repos to speedup tests
  class LimitedHash(s: SparkSession, log: Slf4jLogger) extends Hash(s, log) {
    override def filesForRepos(repos: DataFrame): DataFrame =
      super.filesForRepos(repos).filter(col("path").isin(filePaths: _*))
  }
  object LimitedHash {
    def apply(s: SparkSession, log: Slf4jLogger): LimitedHash = new LimitedHash(s, log)
  }

  def hashWithNewGemini(): HashResult = {
    val gemini = Gemini(sparkSession)
    val hash = LimitedHash(sparkSession, log)
    val repos = gemini.getRepos("src/test/resources/siva/duplicate-files")
    hash.forRepos(repos)
  }

  "Hash" should "return correct files" in {
    val hashResult = hashWithNewGemini()
    val files = hashResult.files
    // num of files * num of repos
    files.count() shouldEqual 6
    // make sure DataFrame contains correct fields
    val row = files.limit(1).select("blob_id", "repository_id", "commit_hash", "path").collect().last
    row.getAs[String]("blob_id") shouldEqual "d8c728246ae60060da0d199f530f47772f89c77b"
    row.getAs[String]("repository_id") shouldEqual "github.com/erizocosmico/borges.git"
    row.getAs[String]("commit_hash") shouldEqual "b1fcd3bf0ba810c05cb418babc09cc7f7783cc03"
    row.getAs[String]("path") shouldEqual ".gitignore"
  }

  "Hash" should "calculate hashes" in {
    val hashes = hashWithNewGemini().hashes

    // num of not-ignored files * num of repos
    hashes.count() shouldEqual 4
    // make sure rdd contains correct values
    val rows = hashes.collect.map(_.doc)
    rows should contain ("github.com/src-d/borges.git//archiver_test.go@7558786958f6084188135b773f4457472a9e4052")
  }

  "Hash" should "generate docFreq" in {
    val docFreq = hashWithNewGemini().docFreq
    // num of processed files * 2 repo
    docFreq.docs shouldEqual 4
    docFreq.tokens.size shouldEqual 738
    docFreq.df(docFreq.tokens.head) shouldEqual 3
  }

  "Hash with limit" should "collect files only from limit repos" in {
    val gemini = Gemini(sparkSession)
    val repos = gemini.getRepos("src/test/resources/siva", 1).select("repository_path").distinct().count()
    repos should be(1)
  }

  ".siva files listing" should "include only files, reachable by Engine" in {
    val path = "src/test/resources/siva"
    val engine = tech.sourced.engine.Engine(sparkSession, path, "siva")
    val repos = engine.getRepositories.select("repository_path").distinct
    println(s"Expected: \n\t${repos.collect.mkString("\n\t")}")

    val reposPaths = HashSparkApp.listRepositories(path, "siva", sparkSession)
    println(s"Actual: \n\t${reposPaths.mkString("\n\t")}")

    reposPaths.size shouldEqual repos.count
  }

  ".siva files listing" should "no include un-reachable .siva files" in {
    val repos = HashSparkApp.listRepositories("src/test", "siva", sparkSession)
    repos.length shouldBe 0
  }

}
