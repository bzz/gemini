package tech.sourced.gemini

import java.nio.ByteBuffer

import com.datastax.driver.core.{Session, SimpleStatement}
import org.slf4j.{Logger => Slf4jLogger}

import scala.collection.JavaConverters._
import scala.collection.mutable

case class FileHash(sha1: String, value: ByteBuffer)

class DBConnectedComponents(
                             log: Slf4jLogger,
                             conn: Session,
                             table: String,
                             keyspace: String = Gemini.defautKeyspace)
  extends ConnectedComponents(log) {
  def getHashtables(): List[Byte] = {
    val hashtablesCql = s"SELECT DISTINCT hashtable FROM $keyspace.$table"

    conn
      .execute(new SimpleStatement(hashtablesCql))
      .asScala
      .map(_.getByte("hashtable"))
      .toList
      .sorted
  }

  def getHashValues(hashtable: Byte): Iterable[FileHash] = {
    val cql = s"SELECT sha1, value FROM $keyspace.$table WHERE hashtable=$hashtable"

    conn.execute(new SimpleStatement(cql))
      .asScala
      .map(row => FileHash(row.getString("sha1"), row.getBytes("value")))
  }
}

abstract class ConnectedComponents(log: Slf4jLogger) {
  def getHashtables(): List[Byte]

  def getHashValues(hashtable: Byte): Iterable[FileHash]

  /**
    * Group sha1-values from hashtables to buckets
    *
    * sequential sha1s with the same value in hashtable are be grouped to one bucket
    * identical sha1s from different hashtables have the same element id
    *
    * it allows to operate with simple list of ints later instead of big ByteBuffers
    *
    * @return Buckets, Element-to-ID
    */
  def makeBuckets(): (List[List[Int]], Map[String, Int]) = {
    val allHashTables = getHashtables()
    if (allHashTables.isEmpty) {
      return (List[List[Int]](), Map[String, Int]())
    }

    val firstHashTable::hashTables = allHashTables

    var buckets = List[List[Int]]()
    var band: Option[ByteBuffer] = None
    var bucket = List[Int]()

    // code for the first hashTable uses the algorithm as for others (bucketsForHashTable methods)
    // but also builds elementIds map
    val elementIds = getHashValues(firstHashTable)
      .zipWithIndex.
      foldLeft(Map[String, Int]()) { (idsMap, item) =>
      val (hash, elId) = item
      val FileHash(sha1, value) = hash

      if (!band.contains(value)) {
        if (bucket.size > 1) {
          buckets = buckets :+ bucket
        }

        bucket = List[Int]()
        band = Some(value)
      }
      bucket = bucket :+ elId

      idsMap + (sha1 -> elId)
    }

    if (bucket.size > 1) {
      buckets = buckets :+ bucket
    }

    buckets ++= hashTables.par.flatMap(bucketsForHashTable(_, elementIds))

    log.info(s"Number of buckets: ${buckets.size}")
    log.info(s"Number of elements: ${elementIds.size}")

    (buckets, elementIds)
  }

  private def bucketsForHashTable(hashTable: Byte, elementIds: Map[String, Int]): List[List[Int]] = {
    var buckets = List[List[Int]]()
    var band: Option[ByteBuffer] = None
    var bucket = List[Int]()

    getHashValues(hashTable).foreach { case FileHash(sha1, value) =>
      val elId = elementIds(sha1)

      if (!band.contains(value)) {
        if (bucket.size > 1) {
          buckets = buckets :+ bucket
        }

        bucket = List[Int]()
        band = Some(value)
      }
      bucket = bucket :+ elId
    }

    if (bucket.size > 1) {
      buckets = buckets :+ bucket
    }

    buckets
  }

  /**
    * Create hash map: element to list of bucket's indices
    *
    * @param buckets List of buckets
    * @return
    */
  def elementsToBuckets(buckets: List[List[Int]]): Map[Int, List[Int]] = {
    val map = buckets
      .zipWithIndex
      .foldLeft(Map[Int, List[Int]]()) { case (elementToBuckets, (bucket, i)) =>
        bucket.foldLeft(elementToBuckets) { (elementToBuckets, el) =>
          var list = elementToBuckets.getOrElse(el, List[Int]())
          list = list :+ i
          elementToBuckets + (el -> list)
        }
      }
    log.info(s"ElementsToBuckets map :${map.size}")
    map
  }

  /**
    * Iterate over buckets and create list of connected components
    *
    * Steps:
    *  - make the list of unvisited buckets
    *  - make a map of connected components (groupId -> list of elements)
    *
    * loop:
    *  - visit last unvisited bucket
    *  - create a new connected components group and put elements of the bucket there
    *  - iterate over elements of the bucket
    *  - if an element appears in another bucket add elements of that bucket to the cc group
    * and mark bucket as visited
    *  - repeat
    *
    * @param buckets          List of buckets
    * @param elementToBuckets Map of elements to bucket indices
    * @return
    */
  def findInBuckets(
                     buckets: List[List[Int]],
                     elementToBuckets: Map[Int, List[Int]]): Map[Int, Set[Int]] = {

    var connectedComponentsElement = Map[Int, Set[Int]]()
    var unvisitedBuckets = List.range(0, buckets.size)
    var ccId = 0 // connected component counter
    while (unvisitedBuckets.nonEmpty) {
      var pending = List[Int](unvisitedBuckets.last)
      unvisitedBuckets = unvisitedBuckets.dropRight(1)
      while (pending.nonEmpty) {
        val bucket = pending.last
        pending = pending.dropRight(1)

        val elements = buckets(bucket)
        var ccElements = connectedComponentsElement.getOrElse(ccId, Set[Int]())
        ccElements = ccElements ++ elements
        connectedComponentsElement = connectedComponentsElement + (ccId -> ccElements)

        elements.foreach { element => {
          val elementBuckets = elementToBuckets(element)
          elementBuckets.foreach { b =>
            if (unvisitedBuckets.contains(b)) {
              pending = pending :+ b
              unvisitedBuckets = unvisitedBuckets.filterNot(_ == b)
            }
          }
        }
        }
      }
      ccId += 1
    }

    log.info(s"CC number: ${connectedComponentsElement.size}")
    connectedComponentsElement
  }
}
