
//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** @author  John Miller, Yang Fan
 *  @version 1.5
 *  @date    Sun Aug 23 15:42:06 EDT 2015
 *  @see     LICENSE (MIT style license file).
 *
 *  An implementation supporting columnar relational databases facilitating easy
 *  and rapid analytics.  The columns in a relation are vectors from the
 *  `scalation.linalgebra` package.  Vectors and matrices may be readily extracted
 *  from a relation and feed into any of the numerous analytics techniques provided
 *  in `scalation.analytics`.  The implementation provides most of the columnar
 *  relational algebra operators given in the following paper:
 *  @see db.csail.mit.edu/projects/cstore/vldb.pdf
 *
 *  Some of the operators have unicode versions: @see `scalation.util.UnicodeTest`
 */

package scalation
package columnar_db

import java.io._

import scala.reflect.ClassTag
import scala.collection.mutable.{ArrayBuffer, Map}
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.collection.mutable.HashMap

import scalation.linalgebra.{Vec, _}
import scalation.linalgebra.MatrixKind._
import scalation.math.{Complex, Rational, Real}
import scalation.math.StrO.StrNum
import scalation.util.{banner, Error, ReArray, getFromURL_File, time}

import TableObj._
import columnar_db._

//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `Relation` companion object provides additional functions for the `Relation`
 *  class.
 */
object Relation
{
    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Create an unpopulated relation.
     *  @param name     the name of the relation
     *  @param key      the column number for the primary key (< 0 => no primary key)
     *  @param domain   an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     *  @param colName  the names of columns
     */
    def apply (name: String, key: Int, domain: String, colName: String*): Relation =
    {
        val n = colName.length
        new Relation (name, colName, Vector.fill [Vec] (n)(null), key, domain)
    } // apply

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Create a relation from a sequence of row/tuples.  These rows must be converted
     *  to columns.
     *  @param name     the name of the relation
     *  @param colName  the names of columns
     *  @param row      the sequence of rows to be converted to columns for the columnar relation
     *  @param key      the column number for the primary key (< 0 => no primary key)
     *  @param domain   an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     */
    def apply (name: String, colName: Seq [String], row: Seq [Row], key: Int, domain: String): Relation =
    {
        val equivCol = Vector.fill [Vec] (colName.length)(null)
        val r2 = new Relation (name, colName, equivCol, key, domain)
        for (tuple <- row) r2.add (tuple)
        r2.materialize ()
    } // apply

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Create a relation from a sequence of row/tuples.  These rows must be converted
     *  to columns.
     *  @param name     the name of the relation
     *  @param colName  the names of columns
     *  @param row      the sequence of rows to be converted to columns for the columnar relation
     *  @param key      the column number for the primary key (< 0 => no primary key)
     */
    def apply (name: String, colName: Seq [String], row: Seq [Row], key: Int): Relation =
    {
        val equivCol = Vector.fill [Vec] (colName.length)(null)
        val r2 = new Relation (name, colName, equivCol, key, null)
        for (tuple <- row) r2.add (tuple)
        r2.materialize ()
    } // apply

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Read the relation with the given 'name' into memory using serialization.
     *  @param name  the name of the relation to load
     */
    def apply (name: String): Relation =
    {
        val ois = new ObjectInputStream (new FileInputStream (STORE_DIR + name + SER))
        val obj = ois.readObject ()
        ois.close ()
        val res = obj.asInstanceOf [Relation]
        res
    } // apply

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Read the relation with the given 'name' into memory loading its columns
     *  with data from the CSV file named 'fileName'.
     *  @param fileName  the file name of the data file
     *  @param name      the name of the relation
     *  @param colName   the names of columns
     *  @param key       the column number for the primary key (< 0 => no primary key)
     *  @param domain    an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     *  @param skip      the number of lines in the CSV file to skip (e.g., header line(s))
     *  @param eSep      the element separation string/regex (e.g., "," ";" " +")
     */
    def apply (fileName: String, name: String, colName: Seq [String], key: Int,
               domain: String, skip: Int, eSep: String): Relation =
    {
        var cnt    = skip
        val lines  = getFromURL_File (fileName)
        val newCol = Vector.fill [Vec] (colName.length)(null)
        val r3     = new Relation (name, colName, newCol, key, domain)
        for (ln <- lines) {
            if (cnt <= 0) r3.add (r3.row (ln.split (eSep), domain)) else cnt -= 1
        } // for
        r3.materialize ()
    } // apply

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Read the relation with the given 'name' into memory loading its columns
     *  with data from the CSV file named 'fileName'.  In this version, the column
     *  names are read from the first line of the file.
     *  @param fileName  the file name of the data file
     *  @param name      the name of the relation
     *  @param key       the column number for the primary key (< 0 => no primary key)
     *  @param domain    an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     *  @param eSep      the element separation string/regex (e.g., "," ";" " +")
     */
    def apply (fileName: String, name: String, key: Int, domain: String, eSep: String): Relation =
    {
        var first = true
        val lines = getFromURL_File (fileName)
        var colBuffer: Array [ArrayBuffer [String]] = null
        var colName: Seq [String] = null
        var newCol: Vector [Vec] = null

        for (ln <- lines) {
            if (first) {
                colName   = ln.split (eSep).map (_.trim)
                colBuffer = Array.ofDim (colName.length)
                for (i <- colBuffer.indices) colBuffer(i) = new ArrayBuffer ()
                first = false
            } else {
                val values = ln.split (eSep).map (_.trim)
                values.indices.foreach (i => { colBuffer(i) += values(i) })
            } // if
        } // for
        new Relation (name, colName, colBuffer.indices.map (i => VectorS (colBuffer(i).toArray)).toVector, key, domain)
    } // apply

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Read the relation with the given 'name' into memory loading its columns
     *  with data from the CSV file named 'fileName'.  In this version, the column
     *  names are read from the first line of the file.  It uses 'col2' which is a
     *  temporary ReArray, and maintains indices.
     *  @param fileName  the file name of the data file
     *  @param name      the name of the relation
     *  @param key       the column number for the primary key (< 0 => no primary key)
     *  @param domain    an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     *  @param eSep      the element separation string/regex (e.g., "," ";" " +")
     */
    def apply (fileName: String, name: String, domain: String, key: Int, eSep: String = ","): Relation =
    {
        var first         = true
        val lines         = getFromURL_File (fileName)
        var r3: Relation  = null
        var currentlineno = 0

        for (ln <- lines) {
            if (first) {
                val colName = ln.split (eSep)
                val newCol  = Vector.fill [Vec] (colName.length)(null)
                r3    = new Relation (name, colName, newCol, key, domain)
                first = false
            } else {
                if (currentlineno % 1000 == 0) println (s"$currentlineno")
                r3.add (r3.row (ln.split (eSep), domain))
                currentlineno += 1
            } // if
        } // for
        r3.materialize ()
    } // apply

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Read the relation with the given 'name' into memory loading its columns
     *  with data from the CSV file named 'fileName'.  This version assumes
     *  defaults for 'eSep' and 'skip' of ("," and 0).
     *  @param fileName  the file name of the data file
     *  @param name      the name of the relation
     *  @param colName   the names of columns
     *  @param key       the column number for the primary key (< 0 => no primary key)
     *  @param domain    an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     */
    def apply (fileName: String, name: String, colName: Seq [String], key: Int,
               domain: String): Relation =
    {
        val eSep   = ","
        val lines  = getFromURL_File (fileName)
        val newCol = Vector.fill [Vec] (colName.length)(null)
        val r3     = new Relation (name, colName, newCol, key, domain)
        for (ln <- lines) r3.add (r3.row (ln.split (eSep), domain))
        r3.materialize ()
    } // apply

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Read the relation with the given 'name' into memory loading its columns
     *  with data from the '.arff' file named 'fileName'.
     *  @param fileName  the file name of the data file
     *  @param key       the column number for the primary key (< 0 => no primary key)
     *  @param domain    an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     */
    def apply (fileName: String, key: Int, domain: String): Relation =
    {
        val eSep = "[, ]"
        val lines = getFromURL_File (fileName)
        var name: String = null
        var colBuffer: Array [ArrayBuffer [String]] = null
        var colName = ArrayBuffer [String]()
        var newCol: Vector [Vec] = null
        var foundData = false
        for (ln <- lines) {
            if (ln.indexOf ("%") == 0) {
                // skip comment
            } else if (ln.indexOf ("@relation") == 0) {
                name = ln.split (eSep)(1)
            } else if (ln.indexOf ("@attribute") == 0) {
                colName += ln.split(eSep)(1)
            } else if (ln.indexOf ("@data") == 0) {
                foundData = true
                colBuffer = Array.ofDim (colName.length)
                for (i <- colBuffer.indices) colBuffer (i) = new ArrayBuffer ()
            } else if (foundData) {
                val values = ln.split (eSep)
                values.indices.foreach (i => { colBuffer (i) += values (i) })
            } // if
        } // for
        new Relation (name, colName, colBuffer.indices.map (i => VectorS (colBuffer(i).toArray)).toVector, key, domain)
    } // apply

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Read the relation with the given 'name' into memory from a JSON file.
     *  @param fileName  the file name of the JSON file
     *  @param name      the name of the relation to load
     */
    def apply (fileName: String, name: String): Relation =
    {
        null                                                     // FIX - needs to be implemented
    } // apply

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Create a relation from the 'xy' matrix of doubles.
     *  @param xy       the matrix containing the data
     *  @param name     the name of the relation
     *  @param colName  the names of columns
     *  @param key      the column number for the primary key (< 0 => no primary key)
     *  @param domain   an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     */
    def fromMatriD (xy: MatriD, name: String, colName: Seq [String], key: Int = -1,
                    domain: String = null): Relation =
    {
        val newCol = for (j <- 0 until xy.dim2) yield xy.col (j).asInstanceOf [Vec]
        new Relation (name, colName, newCol.toVector, key, domain)
    } // fromMatriD

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Create a relation from the 'x' matrix of doubles and 'y' vector of doubles
     *  or integers.
     *  @param x        the matrix containing the data
     *  @param y        the vector containing the data
     *  @param name     the name of the relation
     *  @param colName  the names of columns
     *  @param key      the column number for the primary key (< 0 => no primary key)
     *  @param domain   an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     */
    def fromMatriD_ (x: MatriD, y: Vec, name: String, colName: Seq [String], key: Int = -1,
                    domain: String = null): Relation =
    {
        val newCol = for (j <- 0 until x.dim2) yield x.col (j).asInstanceOf [Vec]
        new Relation (name, colName, newCol.toVector :+ y, key, domain)
    } // fromMatriD_

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Create a relation from the 'xy' matrix of integers.
     *  @param xy       the matrix containing the data
     *  @param name     the name of the relation
     *  @param colName  the names of columns
     *  @param key      the column number for the primary key (< 0 => no primary key)
     *  @param domain   an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     */
    def fromMatriI (xy: MatriI, name: String, colName: Seq [String], key: Int = -1,
                    domain: String = null): Relation =
    {
        val newCol = for (j <- 0 until xy.dim2) yield xy.col (j).asInstanceOf [Vec]
        new Relation (name, colName, newCol.toVector, key, domain)
    } // fromMatriI

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Create a relation from the 'xy' matrix of integers and 'y' vector of integers.
     *  @param x        the matrix containing the data
     *  @param y        the vector containing the data
     *  @param name     the name of the relation
     *  @param colName  the names of columns
     *  @param key      the column number for the primary key (< 0 => no primary key)
     *  @param domain   an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
     */
    def fromMatriII (x: MatriI, y: VectorI, name: String, colName: Seq [String], key: Int = -1,
                     domain: String = null): Relation =
    {
        val newCol = for (j <- 0 until x.dim2) yield x.col (j).asInstanceOf [Vec]
        new Relation (name, colName, newCol.toVector :+ y, key, domain)
    } // fromMatriII

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the count (number of elements) of each of the columns of columnar
     *  relation 'r'.
     *  @param r  the given relation
     */
    def count (r: Relation): Seq [Any] = for (j <- r.col.indices) yield r.col(j).size

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the minimum of each of the columns of columnar relation 'r'.
     *  @param r  the given relation
     */
    def min (r: Relation): Seq [Any] = for (c <- r.col) yield Vec.min (c)

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the maximum of each of the columns of columnar relation 'r'.
     *  @param r  the given relation
     */
    def max (r: Relation): Seq [Any] = for (c <- r.col) yield Vec.max (c)

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Compute the mean of each of the columns of columnar relation 'r'.
     *  @param r  the given relation
     */
    def sum (r: Relation): Seq [Any] = for (c <- r.col) yield Vec.sum (c)

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Compute the mean of each of the columns of columnar relation 'r'.
     *  @param r  the given relation
     */
    def mean (r: Relation): Seq [Any] = for (c <- r.col) yield Vec.mean (c)

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Compute the mean of each of the columns of columnar relation 'r'.
     *  @param r  the given relation
     */
    def Ɛ (r: Relation): Seq [Any] = for (c <- r.col) yield Vec.mean (c)

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Compute the variance of each of the columns of columnar relation 'r'.
     *  @param r  the given relation
     */
    def variance (r: Relation): Seq [Any] = for (c <- r.col) yield Vec.variance (c)

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Compute the variance of each of the columns of columnar relation 'r'.
     *  @param r  the given relation
     */
    def Ʋ (r: Relation): Seq [Any] = for (c <- r.col) yield Vec.variance (c)

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Compute the correlation of column 'i' and 'j' within columnar relation 'r'.
     *  @param r  the given relation
     *  @param i  the first column vector
     *  @param j  the second column vector
     */
    def corr (r: Relation, i: Int = 0, j: Int = 1): Double = Vec.corr (r.col(i), r.col(j))

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Get a Vec of sum of the 'cName' column for the 'r' relation base on each group,
     *  the result will be the same size.
     *  @param r      the relation to operate on
     *  @param cName  sum on column "cName"
     */
    def sum2 (r: Relation, cName: String): Vec =
    {
        val cPos    = r.colMap.get(cName).get
        val domainc = r.domain(cPos)
        var columnlist:Vec = null
        var count   = 0
        var pointer = 0
        var sumlist: Vec = null
        for (idx <- r.orderedIndex) {
//          columnlist = Vec.:+ (columnlist,r.index(idx)(cPos),r.domain,cPos)
            columnlist = Vec.:+ (columnlist,r.index(idx)(cPos))
            if (count +1 == r.grouplist(pointer)) {
                val thisroundsum = Vec.sum(columnlist)
//              sumlist = Vec.:+ (sumlist, thisroundsum, r.domain, cPos)
                sumlist = Vec.:+ (sumlist, thisroundsum)
                columnlist = null
                pointer += 1
            } // if
            count += 1
        } // for
        sumlist
    } // sum2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Get a Vec of max of the 'cName' column for the 'r' relation.
     *  @param r the relation you want to operate on
     *  @param cName  max on column "cName"
     */
    def max2 (r: Relation, cName: String): Vec =
    {
        val cPos    = r.colMap.get(cName).get
        val domainc = r.domain(cPos)
        var columnlist:Vec = null
        var count   = 0
        var pointer = 0
        var maxlist: Vec=null
        for(idx <- r.orderedIndex) {
//          columnlist = Vec.:+ (columnlist,r.index(idx)(cPos),r.domain,cPos)
            columnlist = Vec.:+ (columnlist,r.index(idx)(cPos))
            if (count +1 == r.grouplist(pointer)) {
                val thisroundsum = Vec.max(columnlist)
//              maxlist = Vec.:+ (maxlist, thisroundsum, r.domain, cPos)
                maxlist = Vec.:+ (maxlist, thisroundsum)
                columnlist = null
                pointer += 1
            } // if
            count += 1
        } // for
        maxlist
    } // max2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Get a Vec of min of the 'cName' column for the 'r' relation
     *  @param r      the relation you want to operate on
     *  @param cName  min on column "cName"
     */
    def min2 (r: Relation, cName: String): Vec =
    {
        val cPos    = r.colMap.get(cName).get
        val domainc = r.domain(cPos)
        var columnlist:Vec = null
        var count   = 0
        var pointer = 0
        var minlist:Vec=null
        for (idx <- r.orderedIndex) {
//          columnlist = Vec.:+ (columnlist,r.index(idx)(cPos),r.domain,cPos)
            columnlist = Vec.:+ (columnlist,r.index(idx)(cPos))
            if (count +1 == r.grouplist(pointer)) {
                val thisroundsum = Vec.min(columnlist)
//              minlist = Vec.:+ (minlist, thisroundsum, r.domain, cPos)
                minlist = Vec.:+ (minlist, thisroundsum)
                columnlist = null
                pointer += 1
            } // if
            count += 1
        } // for
        minlist
    } // min2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Get a Vec of average of the 'cName' column for the 'r' relation.
     *  @param r      the relation you want to operate on
     *  @param cName  average on column "cName"
     */
    def avg2 (r: Relation, cName: String): Vec =
    {
        val cPos    = r.colMap.get(cName).get
        val domainc = r.domain(cPos)
        var columnlist: Vec = null
        var count   = 0
        var pointer = 0
        var avglist: Vec = null
        for (idx <- r.orderedIndex) {
//          columnlist = Vec.:+ (columnlist, r.index(idx)(cPos), r.domain, cPos)
            columnlist = Vec.:+ (columnlist, r.index(idx)(cPos))
            if (count + 1 == r.grouplist(pointer)) {
                val thisroundsum = Vec.mean(columnlist)
//              avglist = Vec.:+ (avglist, thisroundsum, r.domain, cPos)
                avglist = Vec.:+ (avglist, thisroundsum)
                columnlist = null
                pointer += 1
            } // if
            count += 1
        } // for
        avglist
    } // avg2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Get a Vec of count of the 'cName' column for the 'r' relation.
     *  @param r      the relation you want to operate on
     *  @param cName  count of column "cName"
     */
    def count2 (r: Relation, cName: String): Vec =
    {
        val cPos   = r.colMap.get(cName).get
        var countlist:Vec = null
        var i: Int = 0
        for(p<-r.grouplist) {
            val count = p - i
//           countlist = Vec.:+ (countlist, count, r.domain, cPos)
            countlist = Vec.:+ (countlist, count)
            i = p
        } // for
        countlist
    } // count2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** From function return cartesian product of all the relations.
     *  @param relations  the relations making up the from clause
     */
    def from (relations: Relation*): Relation =
    {
        var result = relations(0)
        for (i <- 1 until relations.size) result = result.cproduct(relations(i))
        result
    } // from

} // Relation object


//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** `RelationPair` defines a case class: a relation pair of relation1 and relation2
  * @param r1  relation 1
  * @param r2  relation 2
  */
case class RelationPair (val r1: Relation, val r2: Table)
{
    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** on function call thetajoin on predicate 'p'.
      * @param  p  the predicate to do theatjoin
      * @tparam T  the predicate type
      */
    def on [T] (p: Predicate2 [T]): Relation = r1.thetajoin (r2, p)

} // RelationPair class

import Relation._

//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `Relation` class stores and operates on vectors.  The vectors form the
 *  columns of the columnar relational datastore.  Columns may have any of the
 *  following types:
 *  <p>
 *      C - `Complex`  - `VectorC` - 128 bit complex number a + bi
 *      D - `Double`   - `VectorD` -  64 bit double precision floating point number
 *      I - `Int`      - `VectorI` -  32 bit integer
 *      L - `Long`     - `VectorL` -  64 bit long integer
 *      Q - `Rational` - `VectorQ` - 128 bit ratio of two long integers
 *      R - `Real`     - `VectorR` - 128 bit quad precision floating point number
 *      S - `StrNum`   - `VectorS` - variable length numeric string
 *  <p>
 *  FIX - (1) don't allow (public) var
          (2) avoid unchecked or incomplete .asInstanceOf [T]
 *------------------------------------------------------------------------------
 *  @param name     the name of the relation
 *  @param colName  the names of columns
 *  @param col      the Scala Vector of columns making up the columnar relation
 *  @param key      the column number for the primary key (< 0 => no primary key)
 *  @param domain   an optional string indicating domains for columns (e.g., 'SD' = 'StrNum', 'Double')
 *  @param fKeys    an optional sequence of foreign keys - Seq (column name, ref table name, ref column position)
 */
class Relation (val name: String, val colName: Seq [String], var col: Vector [Vec],
                val key: Int = 0, val domain: String = null, var fKeys: Seq [(String, String, Int)] = null)
     extends Table with Error
{
//  private   val serialVersionUID = 1L
    private   val DEBUG        = true                                           // debug flag
    protected val colMap       = Map [String, Int] ()                           // map column name -> column number
    @transient
    private   val col2         = Vector.fill (colName.size)(new ReArray [Any])  // efficient holding area for building columns
    private   var grouplist    = Vector [Int] ()                                // rows in group
    protected val index        = Map [KeyType, Row] ()                          // index that maps a key into row
    protected val indextoKey   = HashMap [Int, KeyType] ()                      // map index -> key
    private   var keytoIndex   = HashMap [KeyType, Int] ()                      // map key -> index
    protected var orderedIndex = Vector [KeyType] ()                            // re-ordering of the key column

    if (colName.length != col.length) flaw ("constructor", "incompatible sizes for 'colName' and 'col'")
    Catalog.add (name, colName, key, domain)

    for (j <- colName.indices) colMap += colName(j) -> j

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** The 'generateIndex' method helps, e.g., the popTable, methods to generate
     *  an index for the table.
     *  @param reset  if reset is true, use old index to build new index; otherwise, create new index
     */
    def generateIndex (reset: Boolean = false)
    {
        if (! reset) {                                                      // create new index
            for (i <- 0 until rows) {
                val mkey = if (key != -1) new KeyType (row(i)(key))         // key column is specified
                           else new KeyType(i)                              // key column is not specified
                val tuple    = row(i)
                index       += mkey -> tuple
                indextoKey  += i -> mkey
                keytoIndex  += mkey -> i
                orderedIndex = orderedIndex :+ mkey
            } // for
        } else {                                                            // use old index to build
            val newoderedIndex = new ReArray [KeyType] ()
            val newkeytoIndex =  new HashMap [KeyType, Int] ()
            for (i <- orderedIndex.indices) {
                val mkey       = if (key != -1) orderedIndex(i) else new KeyType (i)
                val tuple      = row(keytoIndex(mkey))
                index         += mkey -> tuple
                newkeytoIndex += mkey -> i
                newoderedIndex.update (newoderedIndex.length, mkey)
            } // for
            orderedIndex = newoderedIndex.toVector                          // map old keytoIndex to rowIndex to
            keytoIndex   = newkeytoIndex
        } // if
    } // generateIndex

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the size in terms of number of columns in the relation.
     */
    def cols: Int = col.length

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the columns in the relation.
     */
    override def columns: Vector [Vec] = col

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the names of columns in the relation.
     */
    def colNames: Seq [String] = colName

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the mapping from column names to column positions.
     */
    def colsMap: Map [String, Int] = colMap

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the domains for the columns in the relation.
     */
    def domains: String = domain

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the size in terms of number of rows in the relation.
     */
    def rows: Int = if (col(0) == null) 0 else col(0).size

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Project onto the columns with the given column names.
     *  @param cName  the names of the columns to project onto
     */
    def pi (cName: String*): Relation = pi (cName.map (colMap (_)), cName)

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Method 'epi' used to project aggregate functions columns,
     *  @param aggF       the aggregate functions you want to use
     *  @param funName   the newly created aggregate columns'names
     *  @param aggFAttr  the columns you want to use of correspondent aggregate functions
     *  @param cName     the columns you want to project on
     */
    def epi (aggF: Seq [AggFunction], funName: Seq [String], aggFAttr: Seq [String], cName: String*): Relation =
    {
        aggFAttr.foreach (a =>
            if (! colName.contains(a)) throw new IllegalArgumentException ("the attribute you want to aggregate on does not exist"))
        cName.foreach (a =>
            if (! colName.contains(a)) throw new IllegalArgumentException ("the attribute you want to project on does not exist"))

        if (grouplist.isEmpty) groupBy (colName(key))
        val newCol     = Vector.fill [Vec](aggFAttr.size + cName.size)(null)
        val colNamenew = cName ++ funName
        var newDomain  = cName.map(n => colMap(n)).map (i => domain(i))
        for (i <- funName.indices) {
            if (funName(i) contains "count") newDomain = newDomain :+ 'I'          // other aggregate's result domain is based on the aggreagte column
            else newDomain = newDomain :+ domain(colMap(aggFAttr(i)))
        } // for
        val r2 = new Relation (name + "_e_" + ucount (), colNamenew, newCol, key, newDomain.mkString (""))
        if (rows == 0) return r2                                                   // no rows means early return

        val agglist = for (i <- aggF.indices) yield aggF(i)(this, aggFAttr(i))
        if (cName.size != 0) {
            val cPos    = cName.map (colMap(_))                                    // position of cName
            val cPos2   = aggFAttr.map (colMap(_))                                 // position of aggregate columns
            val shrinkR = pi(cPos, null)                                           // projected relation
            var row_i   = 0
            var group_j = 0
            orderedIndex.foreach (idx => {
                var thisrow = shrinkR.row(keytoIndex(idx))
                for (aggf <- agglist.indices) thisrow = thisrow :+ Vec (agglist(aggf), group_j)
                r2.add_ni (thisrow)
                row_i += 1
                if (row_i == grouplist(group_j)) group_j += 1
            }) // foreach
            r2.materialize ()
        } else {                                                                   // only project on the aggregate column
            for (i <- aggF.indices) {
                r2.col = if (i == 0) Vector (agglist(i)) else r2.col :+ agglist(i)
            } // for
        } // if
        r2
    } // epi

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Method 'epiAny' is a special case of epi.  When the projected columns can not be
     *  decided by the group by columns, only one representative will be shown for each group.
     *  @param aggF      the aggregate functions you want to use
     *  @param funName   the newly created aggregate columns'names
     *  @param aggFAttr  the columns you want to use of correspondent aggregate functions
     *  @param cName     the columns you want to project on
     */
    def epiAny (aggF: Seq [AggFunction], funName: Seq [String], aggFAttr: Seq [String], cName: String*): Relation =
    {
        aggFAttr.foreach (a =>
            if (! colName.contains(a)) throw new IllegalArgumentException("the attribute you want to aggregate on does not exists"))
        cName.foreach (a =>
            if (! colName.contains(a)) throw new IllegalArgumentException("the attribute you want to project on does not exists"))

        if (grouplist.isEmpty) groupBy (colName(key))
        val newCol = Vector.fill [Vec](aggFAttr.size + cName.size)(null)
        val colNamenew = cName ++ funName
        var newDomain = cName.map (n => colMap(n)).map (i => domain(i))
        for (i <- funName.indices) {
            newDomain = if (funName(i) contains "count") newDomain :+ 'I'
                        else newDomain :+ domain(colMap(aggFAttr(i)))
        } // for
        val r2 = new Relation (name + "_e_" + ucount (), colNamenew, newCol, key, newDomain.mkString (""))
        if (rows == 0) return r2

        val agglist = for (i <- aggF.indices) yield aggF(i)(this, aggFAttr(i))
        var group_j = 0
        if (cName.size != 0) {
            val cPos = cName.map (colMap(_))
            val shrinkR = pi(cPos, null)
            grouplist.foreach (idx => {
                var newrow: Vector[Any] = null
                val rownumber = keytoIndex(orderedIndex(idx-1))
                newrow        = shrinkR.row(rownumber)
                for (i<- aggF.indices) {
                    val aggtemp = Vec (agglist(0), group_j)
                    newrow      = newrow:+ aggtemp
                } // for
                r2.add_ni (newrow)
                group_j += 1
            }) // foreach
        } // if
        r2.materialize ()
    } // epiAny

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Project onto the columns with the given column names.
     *  @param cName  the names of the columns to project onto
     */
    def π (cName: String*): Relation = pi (cName.map(colMap (_)), cName)

    def pi (cPos: Seq [Int], cName: Seq [String] = null): Relation =
    {
        val newCName  = if (cName == null) cPos.map (colName(_)) else cName
        val newCol    = cPos.map (col(_)).toVector
        val newKey    = if (cPos contains key) key else -1
        val newDomain = projectD (domain, cPos)
        val r2 = new Relation (name + "_p_" + ucount (), newCName, newCol, newKey, newDomain)
        r2
    } // pi

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** FIX - describe and given return type.
     *  @param cName  the names of the columns
     */
    private def getNew (cName: String) = { val cn = colMap (cName)
                                           (cn,
                                            Seq (cName),
                                            if (cn == key) key else -1,
                                            projectD (domain, Seq (cn))) }

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Select elements from column 'cName' in 'this' relation that satisfy the
     *  predicate 'p' and project onto that column.
     *  @param cName  the name of the column used for selection
     *  @param p      the predicate (`Boolean` function) to be satisfied
     */
    def pisigmaC (cName: String, p: Complex => Boolean): Relation =
    {
        val nu     = getNew (cName)
        val newCol = Vector (col (nu._1).asInstanceOf [VectorC].filter (p))
        new Relation (name + "_s_" + ucount (), nu._2, newCol, nu._3, nu._4)
    } // pisigmaC

    def pisigmaD (cName: String, p: Double => Boolean): Relation =
    {
        val nu     = getNew (cName)
        val newCol = Vector (col (nu._1).asInstanceOf [VectorD].filter (p))
        new Relation (name + "_s_" + ucount (), nu._2, newCol, nu._3, nu._4)
    } // pisigmaD

    def pisigmaI (cName: String, p: Int => Boolean): Relation =
    {
        val nu     = getNew (cName)
        val newCol = Vector (col (nu._1).asInstanceOf [VectorI].filter (p))
        new Relation (name + "_s_" + ucount (), nu._2, newCol, nu._3, nu._4)
    } // pisigmaI

    def pisigmaL (cName: String, p: Long => Boolean): Relation =
    {
        val nu     = getNew (cName)
        val newCol = Vector (col (nu._1).asInstanceOf [VectorL].filter (p))
        new Relation (name + "_s_" + ucount (), nu._2, newCol, nu._3, nu._4)
    } // pisigmaL

    def pisigmaQ (cName: String, p: Rational => Boolean): Relation =
    {
        val nu     = getNew (cName)
        val newCol = Vector (col (nu._1).asInstanceOf [VectorQ].filter (p))
        new Relation (name + "_s_" + ucount (), nu._2, newCol, nu._3, nu._4)
    } // pisigmaQ

    def pisigmaR (cName: String, p: Real => Boolean): Relation =
    {
        val nu     = getNew (cName)
        val newCol = Vector (col (nu._1).asInstanceOf [VectorR].filter (p))
        new Relation (name + "_s_" + ucount (), nu._2, newCol, nu._3, nu._4)
    } // pisigmaR

    def pisigmaS (cName: String, p: StrNum => Boolean): Relation =
    {
        val nu     = getNew (cName)
        val newCol = Vector (col (nu._1).asInstanceOf [VectorS].filter (p))
        new Relation (name + "_s_" + ucount (), nu._2, newCol, nu._3, nu._4)
    } // pisigmaS

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Select elements from columns in 'cName' in 'this' relation that satisfy
     *  the predicate 'p'.
     *  @param cName  the name of the column used for selection
     *  @param p      the predicate (`Boolean` function) to be satisfied
     */
    def sigma [T <: Any] (cName: String, p: T => Boolean): Relation =
    {
        if (domain != null) {
            domain(colMap (cName)) match {
            case 'D' => selectAt (selectD (cName, p.asInstanceOf [Double => Boolean]))
            case 'I' => selectAt (selectI (cName, p.asInstanceOf [Int => Boolean]))
            case 'L' => selectAt (selectL (cName, p.asInstanceOf [Long => Boolean]))
            case 'S' => selectAt (selectS (cName, p.asInstanceOf [StrNum => Boolean]))
            case _  => { flaw ("sigma", "predicate type not supported"); null }
            } // match
        } else {
            flaw ("sigma", "optional domains not given - use type specific sigma?")
            null
        } // if
    } // sigma

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Select elements from columns in 'cName' in 'this' relation that satisfy
     *  the predicate 'p'.
     *  @param cName  the name of the column used for selection
     *  @param p      the predicate (`Boolean` function) to be satisfied
     */
    def σ [T <: Any] (cName: String, p: T => Boolean): Relation =
    {
        if (domain != null) {
            domain(colMap (cName)) match {
            case 'D' => selectAt (selectD (cName, p.asInstanceOf [Double => Boolean]))
            case 'I' => selectAt (selectI (cName, p.asInstanceOf [Int => Boolean]))
            case 'L' => selectAt (selectL (cName, p.asInstanceOf [Long => Boolean]))
            case 'S' => selectAt (selectS (cName, p.asInstanceOf [StrNum => Boolean]))
            case _  => { flaw ("σ", "predicate type not supported"); null }
            } // match
        } else {
            flaw ("σ", "optional domains not given - use type specific sigma?")
            null
        } // if
    } // σ

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Select elements from columns in 'cName' in 'this' relation that satisfy
     *  the predicate 'p'.
     *  @param cName  the name of the column used for selection
     *  @param p      the predicate (`Boolean` function) to be satisfied
     */
    def sigmaC (cName: String, p: Complex => Boolean): Relation = selectAt (selectC (cName, p))

    def sigmaD (cName: String, p: Double => Boolean): Relation = selectAt (selectD (cName, p))

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** The parellel version of 'selectD',
     *  @param cName column to select on
     *  @param p  predicate to select
     */
    def sigmaDpar (cName: String, p: Double => Boolean): Relation =
    {
        val filtercol = new scalation.linalgebra.par.VectorD (col (colMap (cName)).asInstanceOf [VectorD].toArray)
        selectAt(filtercol.filterPos (p))
    } // sigmaDpar

    def sigmaI (cName: String, p: Int => Boolean): Relation = selectAt (selectI (cName, p))

    def sigmaL (cName: String, p: Long => Boolean): Relation = selectAt (selectL (cName, p))

    def sigmaQ (cName: String, p: Rational => Boolean): Relation = selectAt (selectQ (cName, p))

    def sigmaR (cName: String, p: Real => Boolean): Relation = selectAt (selectR (cName, p))

    def sigmaS (cName: String, p: StrNum => Boolean): Relation = selectAt (selectS (cName, p))

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Select the positions of elements from columns in 'cName' in 'this' relation
     *  that satisfy the predicate 'p'.
     *  @param cName  the name of the column used for selection
     *  @param p      the predicate (`Boolean` function) to be satisfied
     */
    def selectC (cName: String, p: Complex => Boolean): Seq [Int] =
    {
        col (colMap (cName)).asInstanceOf [VectorC].filterPos (p)
    } // selectC

    def selectD (cName: String, p: Double => Boolean): Seq [Int] =
    {
        col (colMap (cName)).asInstanceOf [VectorD].filterPos (p)
    } // selectD

    def selectI (cName: String, p: Int => Boolean): Seq [Int] =
    {
        col (colMap (cName)).asInstanceOf [VectorI].filterPos (p)
    } // selectI

    def selectL (cName: String, p: Long => Boolean): Seq [Int] =
    {
        col (colMap (cName)).asInstanceOf [VectorL].filterPos (p)
    } // selectL

    def selectQ (cName: String, p: Rational => Boolean): Seq [Int] =
    {
        col (colMap (cName)).asInstanceOf [VectorQ].filterPos (p)
    } // selectQ

    def selectR (cName: String, p: Real => Boolean): Seq [Int] =
    {
        col (colMap (cName)).asInstanceOf [VectorR].filterPos (p)
    } // selectR

    def selectS (cName: String, p: StrNum => Boolean): Seq [Int] =
    {
        col (colMap (cName)).asInstanceOf [VectorS].filterPos (p)
    } // selectS

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Select across all columns at the specified column positions.
     *  @param pos  the specified column positions
     */
    def selectAt (pos: Seq [Int]): Relation =
    {
        val newCol = (for (j <- col.indices) yield Vec.select (col(j), pos)).toVector
        new Relation (name + "_s_" + ucount (), colName, newCol, key, domain)
    } // selectAt

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** The where function filters on predicates (logic is and),
     *  returning the relation satisfying the predicates (column compare with constant)
     *  @param  p  tuple(1): column name, tuple(2): predicate (T => Boolean)
     *  @tparam T  the predicate type
     */
    def where [T] (p: Predicate [T]*): Relation =
    {
        var pos = ArrayBuffer [Int] ()
        for (i <- p.indices) {
            domain(colMap(p(i)._1)) match {
            case 'D' =>
                val pos1 = col(colMap(p(i)._1)).asInstanceOf [VectorD].filterPos (p(i)._2.asInstanceOf [Double => Boolean])
                if (i > 0) pos = pos intersect pos1 else pos ++= pos1
            case 'I' =>
                val pos1 = col(colMap(p(i)._1)).asInstanceOf [VectorI].filterPos (p(i)._2.asInstanceOf [Int => Boolean])
                if (i > 0) pos = pos intersect pos1 else pos ++= pos1
            case 'L' =>
                val pos1 = col(colMap(p(i)._1)).asInstanceOf [VectorL].filterPos (p(i)._2.asInstanceOf [Long => Boolean])
                if (i > 0) pos = pos intersect pos1 else pos ++= pos1
            case 'S' =>
                val pos1 = col(colMap(p(i)._1)).asInstanceOf [VectorS].filterPos (p(i)._2.asInstanceOf [StrNum => Boolean])
                if (i > 0) pos = pos intersect pos1 else pos ++= pos1
            case _ =>
                flaw ("where", "predicate type not supported")
                null
            } // match
        } // for
        selectAt (pos)
    } // where

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Group the relation by specified column names, return a relation
     *  @param cName group columns
     */
    def groupBy (cName: String*): Relation =
    {
        if (! cName.map (c => colName contains(c)).reduceLeft (_ && _))
            flaw ("groupBy", "groupbyName used to groupby doesn't exist in the cName")
        val equivCol = Vector.fill [Vec] (colName.length)(null)
        if (rows == 0) return this

        val cPos = cName.map (colMap (_))

        //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
        /*  Sort on the given columns.
         *  @param sortColumn  the set of columns to sort on
         */
        def sortcol (sortColumn: Set [Any]): Vec =
        {
            var colcol: Vec = null
            val domain = null
//          for (x <- sortColumn) colcol = Vec.:+ (colcol, x, domain, 0)
            for (x <- sortColumn) colcol = Vec.:+ (colcol, x)

            colcol match {
            case _: VectoC => val sortcol = colcol.asInstanceOf [VectoC]; sortcol.sort (); sortcol
            case _: VectoD => val sortcol = colcol.asInstanceOf [VectoD]; sortcol.sort (); sortcol
            case _: VectoI => val sortcol = colcol.asInstanceOf [VectoI]; sortcol.sort (); sortcol
            case _: VectoL => val sortcol = colcol.asInstanceOf [VectoL]; sortcol.sort (); sortcol
            case _: VectoQ => val sortcol = colcol.asInstanceOf [VectoQ]; sortcol.sort (); sortcol
            case _: VectoR => val sortcol = colcol.asInstanceOf [VectoR]; sortcol.sort (); sortcol
            case _: VectoS => val sortcol = colcol.asInstanceOf [VectoS]; sortcol.sort (); sortcol
            case _ => println ("sortcol: vector type not supported"); null.asInstanceOf [Vec]
            } // match
        } // sortcol

        var groupIndexMap = Map [Any, Vector [KeyType]] ()
        val tempIndexMap  = Map [Any, Vector [KeyType]] ()
        var sortlst: Vec  = null

        for (i <- cPos.indices) {
            if (i == 0) {
                index.foreach(indexmap => {
                    val key   = StrNum(indexmap._2(cPos(i)).toString)
                    val value = indexmap._1
                    if (groupIndexMap.contains(key)) groupIndexMap += key -> (groupIndexMap(key) :+ value)
                    else groupIndexMap += key -> Vector(value)
                }) // foreach
            } else {
                tempIndexMap.clear ()
                groupIndexMap.foreach (groupindexmap => {
                    val tempidxlist = groupindexmap._2
                    for (idx <- tempidxlist) {
                        val key   = StrNum(groupindexmap._1.toString + "," + index(idx)(cPos(i)))
                        val value = idx
                        if (tempIndexMap.contains(key)) tempIndexMap += key -> (tempIndexMap(key) :+ value)
                        else tempIndexMap += key -> Vector(value)
                    } // for
                }) // for each
                groupIndexMap = tempIndexMap
            } // if

            if (i == cPos.size - 1) {
                orderedIndex = Vector ()
                grouplist    = Vector [Int] ()
                sortlst      = sortcol(groupIndexMap.keySet.toSet)
                for (k <- 0 until sortlst.size) {
                    val indexes  = groupIndexMap(Vec(sortlst, k))
                    orderedIndex = orderedIndex ++ indexes
                    grouplist    = grouplist :+ orderedIndex.length
                } // for
            } // if
        } // for
        this
    } // groupby

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Update the column named 'cName' using function 'func' for elements with
     *  value 'matchStr'.
     *  @param cName     the name of the column to be updated
     *  @param func      the function used to assign updated values
     *  @param matchStr  the string to be matched to elements
     */
    def update [T <: Any] (cName: String, func: () => String, matchStr: String)
    {
        val colPos = colMap (cName)
        val c = col(colPos)
        for (i <- 0 until c.size if Vec(c, i) == matchStr) Vec(c, i) = func
    } // update

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Update the column named 'cName' using function 'func' for elements where
     *  the predicate 'pred' evaluates to true.
     *  @param cName  the name of the column to be updated
     *  @param func   the function used to assign updated values         // FIX - generalize type
     *  @param pred   the predicated used to select elements for update
     */
//  def update [T <: Any] (cName: String, func: () => String, pred: () => Boolean)
//  {
//      // FIX - to be implemented
//  } // update

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Determine whether 'this' relation and 'r2' are incompatible by having
     *  differing numbers of columns or differing domain strings.
     *  @param r2  the other relation/table
     */
    def incompatible (r2: Table): Boolean =
    {
         if (cols != r2.cols) {
             flaw ("incompatible", s"${this.name} and r2 have differing number of columns")
             true
         } else if (domains != r2.domains) {
             flaw ("incompatible", "${this.name} and r2 have differing domain strings")
             true
         } else {
             false
         } // if
    } // incompatible

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Union 'this' relation and 'r2'.  Check that the two relations are compatible.
     *  If they are not, return the first 'this' relation.
     *  @param r2  the other relation
     */
    def union (r2: Table): Relation =
    {
        if (incompatible (r2)) return this                // take only this relation

//      if (col(0) == null) return if (r2.col(0) == null) null else r2
//      else if (r2.col(0) == null) return this

        val newCol = (for (j <- col.indices) yield Vec.++ (col(j), r2.columns(j)))
        new Relation (name + "_u_" + ucount (), colName, newCol.toVector, -1, domain)
    } // union

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Union 'this' relation and 'r2'.  Check that the two relations are compatible.
     *  @param r2  the other relation
     */
    def ⋃ (r2: Table): Relation = this union r2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Intersect 'this' relation and 'r2'.  Check that the two relations are compatible.
     *  @param r2  the other relation
     */
    def intersect (r2: Table): Relation =
    {
        if (incompatible (r2)) return null
        val newCol = Vector.fill [Vec] (colName.length)(null)
        val r3 = new Relation (name + "_u_" + ucount (), colName, newCol.toVector, -1, domain)
        for (i <- 0 until rows if r2 contains row(i)) r3.add (row(i))
        r3.materialize ()
    } // intersect

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Intersect2 'this' relation and 'r2'.  Check that the two relations are compatible.
     *  Use index to finish intersect operation.
     *  @param _r2  the other relation
     */
    def intersect2 (_r2: Table): Relation =
    {
        val r2 = _r2.asInstanceOf [Relation]
        if (incompatible (r2)) return null

        val newCol = Vector.fill [Vec] (colName.length)(null)
        val r3     = new Relation (name + "_u_" + ucount (), colName, newCol, -1, domain)
        for (key <- orderedIndex) {
            if (r2.orderedIndex contains key) {
                val thisrow = index(key)
                if (thisrow sameElements (r2.index(key))) r3.add_ni (thisrow)
            } // if
        } // for
        r3.materialize ()
    } // intersect2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Intersect 'this' relation and 'r2'.  Check that the two relations are compatible.
     *  @param r2  the other relation
     */
    def ⋂ (r2: Table): Relation = this intersect r2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Take the difference of 'this' relation and 'r2' ('this - r2').  Check that
     *  the two relations are compatible.
     *  @param r2  the other relation
     */
    def - (r2: Table): Relation =
    {
        if (incompatible (r2)) return null
        val newCol = Vector.fill [Vec] (colName.length)(null)
        val r3 = new Relation (name + "_m_" + ucount (), colName, newCol, key, domain)
        for (i <- 0 until rows if ! (r2 contains row(i))) r3.add (row(i))
        r3.materialize ()
    } // -

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Compute the Cartesian product of two tables and return it.
     *  @param r2  the second relation
     */
    def cproduct (r2: Table): Relation =
    {
        val ncols     = cols + r2.cols
        val newCName  = disambiguate (colName, r2.colNames)
        val newCol    = Vector.fill [Vec] (ncols) (null)
        val newKey    = key                                        // FIX
        val newDomain = domain + r2.domains
        val r3 = new Relation (name + "_j_" + ucount (), newCName, newCol, newKey, newDomain)

        for (i <- 0 until rows) {
            val t = row(i)
            for (j <- 0 until r2.rows){
                val u = r2.row(j)
                r3.add (t ++ u)
            } // for
        } // for
        r3.materialize ()
    } // cproduct

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Join 'this' relation and 'r2 by performing an "equi-join".  Rows from both
     *  relations are compared requiring 'cName1' values to equal 'cName2' values.
     *  Disambiguate column names by appending "2" to the end of any duplicate column name.
     *  FIX - only allows single attribute in join condition
     *  @param cName1  the join column names of this relation (e.g., the Foreign Key)
     *  @param cName2  the join column names of relation r2 (e.g., the Primary Key)
     *  @param r2      the rhs relation in the join operation
     */
    def join (cName1: Seq [String], cName2: Seq [String], r2: Table): Relation =
    {
        val ncols = cols + r2.cols
        val cp1   = cName1.map (colMap (_))                        // get column positions in 'this'
        val cp2   = cName2.map (r2.colsMap (_))                    // get column positions in 'r2'
        if (cp1.length != cp2.length) flaw ("join", "incompatible sizes on match columns")

        val newCName  = disambiguate (colName, r2.colNames)
        val newCol    = Vector.fill [Vec] (ncols) (null)
        val newKey    = key                                        // FIX
        val newDomain = domain + r2.domains
        val r3 = new Relation (name + "_j_" + ucount (), newCName, newCol, newKey, newDomain)

        for (i <- 0 until rows) {
            val t = row(i)
            for (j <- 0 until r2.rows) {
                val u = r2.row(j)
                if (sameOn (t, u, cp1, cp2)) r3.add (t ++ u)
            } // for
        } // for
        r3.materialize ()
    } // join

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Join 'this' relation and 'r2 by performing an "equi-join".  Rows from both
     *  relations are compared requiring 'cName1' values to equal 'cName2' values.
     *  Disambiguate column names by appending "2" to the end of any duplicate column name.
     *  @param cName1  the string of join column names of this relation (e.g., the Foreign Key)
     *  @param cName2  the string of join column names of relation r2 (e.g., the Primary Key)
     *  @param r2      the rhs relation in the join operation
     */
    def join (cName1: String, cName2: String, r2: Table): Relation =
    {
        join (cName1.split (" "), cName2.split (" "), r2)
    } // join

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Join 'this' relation and 'r2 by performing an "equi-join", use index to join
     *  FIX - only allows single attribute in join condition
     *  @param cName1  the join column names of this relation (e.g., the Foreign Key)
     *  @param cName2  the join column names of relation r2 (e.g., the Primary Key)
     *  @param _r2     the rhs relation in the join operation
     */
    def joinindex (cName1: Seq [String], cName2: Seq [String], _r2: Table): Relation =
    {
        val r2 = _r2.asInstanceOf [Relation]
        val ncols = cols + r2.cols
        val cp1   = cName1.map (colMap (_))                        // get column positions in 'this'
        val cp2   = cName2.map (r2.colMap (_))                     // get column positions in 'r2'
        if (cp1.length != cp2.length) flaw ("join", "incompatible sizes on match columns")

        val newCName = disambiguate (colName, r2.colName)
        val newCol   = Vector.fill [Vec] (ncols)(null)
        val newKey   = if (r2.key == cp2(0))   key                 // foreign key in this relation
                       else if (key == cp1(0)) r2.key              // foreign key in r2 table
                       else -1                                     // key not in join and composite keys not allowed

        val newDomain = domain + r2.domains
        val r3 = new Relation (name + "_j_" + ucount (), newCName, newCol, newKey, newDomain)

        if (cp1.size == 1 && cp2.size == 1) {
            if (key == cp1(0) && r2.key == cp2(0)) {
                for (k <- orderedIndex) {
                    val t = index(k)
                    val u = r2.index.getOrElse (k, null)
                    if (u != null) r3.add_ni (t ++ u)
                } // for
            } else if (key == cp1(0)) {
                for (idx <- r2.orderedIndex) {
                    val u = r2.index(idx)
                    val t = index.getOrElse (new KeyType (u(cp2(0))), null)
                    if (t != null) r3.add_ni (t ++ u)
                    r3.add_ni(t ++ u)
                } // for
            } else if (r2.key == cp2(0)) {
                for (idx <- orderedIndex) {
                    val t = index(idx)
                    val u = r2.index.getOrElse (new KeyType (t(cp1(0))), null)
                    if (u != null) r3.add_ni (t ++ u)
                } // for
            } // if
        } // if
        r3.materialize ()
    } // joinindex

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Join 'this' relation and 'r2 by performing a "natural-join".  Rows from both
     *  relations are compared requiring 'cName' values to be equal.
     *  @param cName  the common join column names for both relation
     *  @param _r2    the rhs relation in the join operation
     */
    def join (cName: Seq [String], _r2: Table): Relation =
    {
        val r2 = _r2.asInstanceOf [Relation]
        val ncols = cols + r2.cols - cName.length
        val cp1   = cName.map (colMap (_))                         // get column positions in 'this'
        val cp2   = cName.map (r2.colMap (_))                      // get column positions in 'r2'
        val cp3   = r2.colName.map (r2.colMap (_)) diff cp2        // 'r2' specific columns

        val newCName  = uniq_union (colName, r2.colName)
        val newCol    = Vector.fill [Vec] (ncols) (null)
        val newKey    = key                                        // FIX
        val newDomain = domain + r2.domains
        val r3 = new Relation (name + "_j_" + ucount (), newCName, newCol, newKey, newDomain)

        for (i <- 0 until rows) {
            val t = row(i)
            for (j <- 0 until r2.rows) {
                val u = r2.row(j)
                if (sameOn (t, u, cp1, cp2)) { val u3 = project (u, cp3); r3.add (t ++ u3) }

            } // for
        } // for
        r3.materialize ()
    } // join

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Join 'this' relation and 'r2 by performing a "natural-join".  Rows from both
     *  relations are compared requiring agreement on common attributes (column names).
     *  @param r2  the rhs relation in the join operation
     */
    def >< (r2: Table): Relation =
    {
        val common = colName intersect r2.colNames
        join (common, r2)
    } // ><

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Join 'this' relation and 'r2 by performing a "natural-join".  Rows from both
     *  relations are compared requiring agreement on common attributes (column names).
     *  @param r2  the rhs relation in the join operation
     */
    def ⋈ (r2: Table): Relation = this >< r2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Join 'this' and table 'r2' returning a `RelationPair`.
     *  @param r2  the other relation/tabel
     */
    def join (r2: Table): RelationPair = RelationPair (this, r2)

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** The theta join, handle the predicates in where are connect by "and" (where a....and b....).
     *  @param _r2  the second relation
     *  @param p    the theta join,  r1 column name, r2 column name, predicate to compare these two column
     */
    def thetajoin [T] (_r2: Table, p: Predicate2 [T]*): Relation =
    {
        val r2        = _r2.asInstanceOf [Relation]
        val ncols     = cols + r2.cols
        val newCName  = disambiguate (colName, r2.colName)
        val newCol    = Vector.fill [Vec] (ncols) (null)
        val newKey    = key                                        // FIX
        val newDomain = domain + r2.domain
        val r3 = new Relation (name + "_j_" + ucount (), newCName, newCol, newKey, newDomain, null)

        var resultlist = Seq [(Int, Int)] ()
        for (i <- p.indices) {
            var resulttemp = Seq [(Int, Int)] ()
            val cp1 = colMap (p(i)._1)
            val cp2 = r2.colMap (p(i)._2)
            if (domain.charAt (cp1) != r2.domain.charAt (cp2))  flaw ("thetajoin", "differing domain strings")
            val psingle = p(i)._3                          // single predicate

            domain (colMap(p(i)._1)) match {
            case 'D' => resulttemp = col(cp1).asInstanceOf [VectorD].filterPos2 (r2.col (cp2).asInstanceOf [VectorD],
                                                           psingle.asInstanceOf [(Double, Double) => Boolean])
            case 'I' => resulttemp = col(cp1).asInstanceOf [VectorI].filterPos2 (r2.col (cp2).asInstanceOf [VectoI],
                                                           psingle.asInstanceOf [(Int, Int) => Boolean])
            case 'L' => resulttemp = col(cp1).asInstanceOf [VectorL].filterPos2 (r2.col (cp2).asInstanceOf [VectorL],
                                                           psingle.asInstanceOf [(Long, Long) => Boolean])
            case 'S' => resulttemp = col(cp1).asInstanceOf [VectorS].filterPos2 (r2.col (cp2).asInstanceOf [VectorS],
                                                           psingle.asInstanceOf [(StrNum, StrNum) => Boolean])
            case 'd' => resulttemp = col(cp1).asInstanceOf [RleVectorD].filterPos2 (r2.col (cp2).asInstanceOf [RleVectorD],
                                                           psingle.asInstanceOf [(Double, Double) => Boolean])
            case 'i' => resulttemp = col(cp1).asInstanceOf [RleVectorI].filterPos2 (r2.col (cp2).asInstanceOf [RleVectorI],
                                                           psingle.asInstanceOf [(Int, Int) => Boolean])
            case 'l' => resulttemp = col(cp1).asInstanceOf [RleVectorL].filterPos2 (r2.col (cp2).asInstanceOf [RleVectorL],
                                                           psingle.asInstanceOf [(Long, Long) => Boolean])
            case 's' => resulttemp = col(cp1).asInstanceOf [RleVectorS].filterPos2 (r2.col (cp2).asInstanceOf [RleVectorS],
                                                           psingle.asInstanceOf [(StrNum, StrNum) => Boolean])
            case _ => flaw ("thetajoin", "domain string is missing"); null
            } // match

            if (DEBUG) println (s"thetajoin: after predicate $i: resulttemp = $resulttemp")
            if (i == 0) resultlist = resulttemp
            else resultlist = resultlist intersect resulttemp
        } // for

        val smallmapbig = resultlist.groupBy (_._1)
        for (i <- smallmapbig.keySet.toVector.sorted) {
            val t = if (key < 0) index(KeyType(i)) else index(indextoKey(i))
            val bigindexs = smallmapbig (i).map (x => x._2)
            for (j <- bigindexs) {
                val u = if (r2.key < 0) r2.index(KeyType (j)) else r2.index(r2.indextoKey(j))
                r3.add (t ++ u)
            } // for
        } // for
        r3.materialize ()
    } // thetajoin

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Parallel Join 'this' relation and 'r2 by performing an equi join on cName1 = cName2 and into k -threads
     *  seperate the lhs into k part join with rhs.
     *  @param cName1  the join column names of lhs relation
     *  @param cName2  the join column names of rhs relation
     *  @param _r2     the rhs relation in the join operation
     *  @param k       parallel run into k parts
     */
    def parjoin (cName1: Seq [String], cName2: Seq [String], _r2: Relation, k: Int): Relation =
    {
        // make the join into k parts of outer table join with inner table
        // outer table is the one without index, partition on the outer table, inner table use index to loop through

        val r2  = _r2.asInstanceOf [Relation]
        val cp1 = cName1.map (colMap (_))                            // get column positions in 'this'
        val cp2 = cName2.map (r2.colMap (_))                         // get column positions in 'r2'
        var futurelist: IndexedSeq [Future [Relation]] = null

        if (key == cp1(0)) futurelist =
            // use left table as inner table (partition on outer table)
            for (i <- 1 to k) yield Future { r2.parjoinsmall (cName1, cName2, this, i, k) }
        else if (r2.key == cp2(0)) futurelist =
            for (i <- 1 to k) yield Future { parjoinsmall (cName1, cName2, r2, i, k) }

        val waitduration = 190.millisecond                           // Need to be FIXED, should be partial to (rows /k)

        //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
        def recur (r1: Relation, kth: Int, relationList: ArrayBuffer [Relation]): Relation =
        {
            if (kth == relationList.size - 1) r1 union relationList(kth)
            else if (r1 != null) recur (r1 union relationList(kth), kth + 1, relationList)
            else                 recur (relationList(kth), kth + 1, relationList)
        } // recur

        //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
        // union the tables together 2 as a group from result of the futurelist
        def foldhalf (fl: IndexedSeq [Future [Relation]]): Relation =
        {
            val relationList = ArrayBuffer [Relation] ()
            for (i <- 0 until fl.size) relationList += Await.result(fl(i), waitduration)
            recur (relationList(0), 1, relationList)
        } // foldhalf

        foldhalf (futurelist)
    } // parjoin

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** The 'parjoinsmall' method serves as the core part for parjoin function, do the
     *  nth part of the parellel join returning  the relation of join of two tables.
     *  @param cName1  the join column names of lhs relation
     *  @param cName2  the join column names of rhs relation
     *  @param _r2     the rhs relation in the join operation
     *  @param nth     the nth part of the parallel join
     *  @param n       parallel run into k parts
     */
    private def parjoinsmall (cName1: Seq [String], cName2: Seq [String], _r2: Table, nth: Int, n: Int): Relation =
    {
        val r2    = _r2.asInstanceOf [Relation]
        val ncols = cols + r2.cols
        val cp1   = cName1.map (colMap (_))                        // get column positions in 'this'
        val cp2   = cName2.map (r2.colMap (_))                     // get column positions in 'r2'
        if (cp1.length != cp2.length) flaw ("join", "incompatible sizes on match columns")

        val newCName  = disambiguate (colName, r2.colName)
        val newCol    = Vector.fill [Vec] (ncols) (null)
        val newKey    = key                                        // FIX
        val newDomain = domain + r2.domain
        val r3        = new Relation (name + "_j_" + ucount (), newCName, newCol, newKey, newDomain)
        val start     = rows/n * (nth-1)
        val end       = if (nth == n) rows-1 else rows/n*nth - 1

        if (cp1.size == 1 && cp2.size == 1) {
            if (key == cp1(0) && r2.key == cp2(0)) {
                for (k <- orderedIndex.slice (start, end + 1)) {
                    val t = index(k)
                    val u = r2.index.getOrElse(k, null)
                    if (u != null) r3.add_ni (t ++ u)
                } //for
            } // if
            // partition the left table
            for (i <- start until end+1) {
                val t = row(i)
                val u = r2.index.getOrElse(new KeyType (t(cp1(0))), null)
                if (u != null) r3.add_ni (t ++ u)
                } // for
        } // if
        r3.materialize ()
    } // parjoinsmall

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Determine whether 'this' relation contains a row matching the given 'tuple'.
     *  @param tuple  an aggregation of columns values (potential row)
     */
    def contains (tuple: Row): Boolean =
    {
        for (i <- 0 until rows if row(i) sameElements tuple) return true
        false
    } // contains

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Create a row by pulling values from all columns at position 'i'.
     *  @param i  the 'i'th position
     */
    def row (i: Int): Row = col.map (Vec (_, i)).toVector

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Add 'tuple' to 'this' relation as a new row.
     *  FIX:  want an efficient, covariant, mutable data structure, but `Array` is invariant.
     *  @param tuple  an aggregation of columns values (new row)
     *  @param typ    the string of corresponding types, e.g., 'SDI'
     *
    def add (tuple: Row)
    {
        col = (for (j <- tuple.indices) yield
            try {
                Vec.:+ (col(j), tuple(j))
            } catch {
                case cce: ClassCastException =>
                    println (s"add: for column $j of tuple $tuple"); throw cce
            } // try
          ).toVector
    } // add
     */

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Add 'tuple' to 'this' relation as a new row.  It use 'col2' as a temp 'col'
     *  to improve performance.
     *  @param tuple  an aggregation of columns values (new row)
     *  @param typ    the string of corresponding types, e.g., 'SDI'
     */
    @throws (classOf [Exception])
    def add (tuple: Row)
    {
        try {
            if (tuple == null) throw new Exception ("add function: tuple is null")
            val rowindex = col2(0).length
            val newkey   = if (key < 0) new KeyType (rowindex) else new KeyType (tuple(key))
            index       += newkey -> tuple
            keytoIndex  += newkey -> rowindex
            orderedIndex = orderedIndex :+ newkey
            indextoKey  += rowindex -> newkey
            for (i <- tuple.indices) col2(i).update (rowindex, tuple(i))
        } catch {
            case ex: NullPointerException =>
                println ("tuple'size is: " + tuple.size)
                println ("col'size is:   " + col.size)
                throw ex
        } // try
    } // add

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Add 'tuple' to 'this' relation as a new row.  It is slower than 'add' method.
     *  Type is determined by sampling values for columns.
     *  @param tuple  an aggregation of columns values (new row)
     *
    def add_2 (tuple: Row)
    {
        index += new KeyType (tuple(key))-> tuple  // hashmap way
        keytoIndex += new KeyType (tuple(key)) ->rows
        col = (for (j <- tuple.indices) yield
                   try {
//                     Vec.:+ (col(j), StrNum (tuple(j).toString), domain, j)             // FIX - allow this option
                       Vec.:+ (col(j), StrNum (tuple(j).toString))
                   } catch {
                       case cce: ClassCastException =>
                           println (s"add: for column $j of tuple $tuple"); throw cce
                   } // try
              ).toVector
    } // add_2
     */

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Add a tuple into the col2, without maintaining the index (No Index (ni),
     *  orderedIndex, keytoIndex and indextoKey.
     *  @param tuple  the tuple to add
     */
    def add_ni (tuple: Row)
    {
        val rowindex = col2(0).length
        for (i <- tuple.indices) col2(i).update (rowindex, tuple(i))
    } // add_ni

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Materialize function copy the temporary 'col2' into 'col'.  It needs to be called
     *  by the end of the relation construction.
     */
    def materialize (): Relation =
    {
        //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
        /*  Transform the j-th column to the appropriate vector type.
         *  @param j  the j-th column index in the relation
         */
        def transform (j: Int): Vec =
        {
            val first = col2(j)(0)
            if (first != null) col2(j).reduceToSize (col2(j).size)
            first match {
            case _: Complex  => val rs = VectorC (col2(j).asInstanceOf [Seq [Complex]]);  col2(j).clear (); rs
            case _: Double   => val rs = VectorD (col2(j).asInstanceOf [Seq [Double]]);   col2(j).clear (); rs
            case _: Int      => val rs = VectorI (col2(j).asInstanceOf [Seq [Int]]);      col2(j).clear (); rs
            case _: Long     => val rs = VectorL (col2(j).asInstanceOf [Seq [Long]]);     col2(j).clear (); rs
            case _: Rational => val rs = VectorQ (col2(j).asInstanceOf [Seq [Rational]]); col2(j).clear (); rs
            case _: Real     => val rs = VectorR (col2(j).asInstanceOf [Seq [Real]]);     col2(j).clear (); rs
            case _: StrNum   => val rs = VectorS (col2(j).asInstanceOf [Seq [StrNum]]);   col2(j).clear (); rs
            case _: String   => val rs = VectorS (col2(j).asInstanceOf [Seq [String]].toArray); col2(j).clear (); rs
            case _           => println (s"materialize.transform ($j): vector type ($first) not supported"); null
            } // match
        } // transform

//      if (DEBUG) println (s"col2 = $col2")
        col = (for (j <- col.indices) yield transform(j)).toVector
        this
    } // materialize

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Materialize2 function copy the temporary 'col2' into 'col'.  It needs to be
     *  called by the end of the relation construction.  It uses domain to transform
     *  the 'col2' to 'col' according to the domain indicator:
     *  <p>
     *      Dense:      C, D, I, L. Q, R, S
     *      Compressed: c, d, i, l. q, r, s
     *      Sparse:     ???
     *  <p>
     */
    def materialize2 (): Relation =
    {
        //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
        /*  Transform the j-th column to the appropriate vector type.
         *  @param j  the j-th column index in the relation
         */
        def transform (j: Int): Vec =
        {
            val dj = domain(j)
            col2(j).reduceToSize (col2(j).size)
            dj match {

            // Upper case type/domain indictors for Dense Vectors
            case 'C' => val rs = VectorC (col2(j).asInstanceOf [Seq [Complex]]);  col2(j).clear; rs
            case 'D' => val rs = VectorD (col2(j).asInstanceOf [Seq [Double]]);   col2(j).clear; rs
            case 'I' => val rs = VectorI (col2(j).asInstanceOf [Seq [Int]]);      col2(j).clear; rs
            case 'L' => val rs = VectorL (col2(j).asInstanceOf [Seq [Long]]);     col2(j).clear; rs
            case 'Q' => val rs = VectorQ (col2(j).asInstanceOf [Seq [Rational]]); col2(j).clear; rs
            case 'R' => val rs = VectorR (col2(j).asInstanceOf [Seq [Real]]);     col2(j).clear; rs
            case 'S' => val rs = VectorS (col2(j).asInstanceOf [Seq [StrNum]]);   col2(j).clear; rs

            // Lower case type/domain indictors for Compressed Vectors
            case 'c' => val rs = RleVectorC (col2(j).asInstanceOf [Seq [Complex]]);  col2(j).clear; rs
            case 'd' => val rs = RleVectorD (col2(j).asInstanceOf [Seq [Double]]);   col2(j).clear; rs
            case 'i' => val rs = RleVectorI (col2(j).asInstanceOf [Seq [Int]]);      col2(j).clear; rs
            case 'l' => val rs = RleVectorL (col2(j).asInstanceOf [Seq [Long]]);     col2(j).clear; rs
            case 'q' => val rs = RleVectorQ (col2(j).asInstanceOf [Seq [Rational]]); col2(j).clear; rs
            case 'r' => val rs = RleVectorR (col2(j).asInstanceOf [Seq [Real]]);     col2(j).clear; rs
            case 's' => val rs = RleVectorS (col2(j).asInstanceOf [Seq [StrNum]]);   col2(j).clear; rs

            // ??? case type/domain indictors for Sparse Vectors
            // FIX - add option for SparseVectorC ...

            case  _  => println ("materialize2.transform ($j) vector type not supported domain ($dj)"); null
            } // match 
        } // transform

//      if (DEBUG) println (s"col2 = $col2")
        col = (for (j <- col.indices) yield transform (j)).toVector
        this
    } //  materialize2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert 'this' relation into a string column by column.
     */
    override def toString: String =
    {
        var sb = new StringBuilder ("Relation(" + name + ", " + key + ",\n" + colName + ",\n")
        for (i <- col.indices) sb.append (col(i) + "\n")
        sb.replace (sb.length-1, sb.length, ")").mkString
    } // toString

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Show 'this' relation row by row.
     */
    def show ()
    {
        val wid    = 18                                             // column width
        val rep    = wid * colName.length                           // repetition = width * # columns
        val title  = s"| Relation name = $name, key-column = $key "

        println (s"|-${"-"*rep}-|")
        println (title + " "*(rep-title.length) + "   |")
        println (s"|-${"-"*rep}-|")
        print ("| "); for (cn <- colName) print (s"%${wid}s".format (cn)); println (" |")
        println (s"|-${"-"*rep}-|")
        for (i <- 0 until rows) {
            print ("| ")
            for (cv <- row(i)) {
                if (cv.isInstanceOf [Double]) print (s"%${wid}g".format (cv))
                else                          print (s"%${wid}s".format (cv))
            } // for
            println (" |")
        } // for
        println (s"|-${"-"*rep}-|")
    } // show

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Show 'this' relation's foreign keys.
     */
    def showFk ()
    {
        val wid    = 18                                            // column width
        val rep    = wid * colName.length                          // repetition = width * # columns
        val title  = s"| Relation name = $name, foreign keys = "
        val fkline = s"| $fKeys "

        println (s"|-${"-"*rep}-|")
        println (title + " "*(rep-title.length) + "   |")
        println (s"|-${"-"*rep}-|")
        println (fkline + " "*(rep-fkline.length) + "   |")
        println (s"|-${"-"*rep}-|")
    } // showFk

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert 'this' relation into a matrix of doubles, e.g.,
     *  <p>
     *       in the regression equation: 'xb = y' create matrix 'xy'
     *  <p>
     *  @param colPos  the column positions to use for the matrix
     *  @param kind    the kind of matrix to create
     */
    def toMatriD (colPos: Seq [Int], kind: MatrixKind = DENSE): MatriD =
    {
        val colVec = for (x <- pi (colPos).col) yield Vec.toDouble (x)
        kind match {
        case DENSE           => MatrixD (colVec)
        case SPARSE          => SparseMatrixD (colVec)
        case SYM_TRIDIAGONAL => SymTriMatrixD (colVec)
        case BIDIAGONAL      => BidMatrixD (colVec)
        case COMPRESSED      => RleMatrixD (colVec)
        } // match
    } // toMatriD

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert 'this' relation into a matrix of doubles and a vector of doubles.
     *  <p>
     *       in the regression equation: 'xb = y' create matrix 'x' and vector 'y'
     *  <p>
     *  @param colPos   the column positions to use for the matrix
     *  @param colPosV  the column position to use for the vector
     *  @param kind     the kind of matrix to create
     */
    def toMatriDD (colPos: Seq [Int], colPosV: Int, kind: MatrixKind = DENSE): (MatriD, VectorD) =
    {
        val colVec = for (x <- pi (colPos).col) yield Vec.toDouble (x)
        kind match {
        case DENSE           => (MatrixD (colVec),       Vec.toDouble (col(colPosV)).toDense.asInstanceOf [VectorD])
        case SPARSE          => (SparseMatrixD (colVec), Vec.toDouble (col(colPosV)).toDense.asInstanceOf [VectorD])
        case SYM_TRIDIAGONAL => (SymTriMatrixD (colVec), Vec.toDouble (col(colPosV)).toDense.asInstanceOf [VectorD])
        case BIDIAGONAL      => (BidMatrixD (colVec),    Vec.toDouble (col(colPosV)).toDense.asInstanceOf [VectorD])
        case COMPRESSED      => (RleMatrixD (colVec),    Vec.toDouble (col(colPosV)).toDense.asInstanceOf [VectorD])
        } // match
    } // toMatriDD

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert 'this' relation into a matrix of doubles and a vector of integers.
     *  <p>
     *       in the regression equation: 'xb = y' create matrix 'x' and vector 'y'
     *  <p>
     *  @param colPos   the column positions to use for the matrix
     *  @param colPosV  the column position to use for the vector
     *  @param kind     the kind of matrix to create
     */
    def toMatriDI (colPos: Seq [Int], colPosV: Int, kind: MatrixKind = DENSE): (MatriD, VectorI) =
    {
        val colVec = for (x <- pi (colPos).col) yield Vec.toDouble (x)
        kind match {
        case DENSE           => (MatrixD (colVec),       Vec.toInt (col(colPosV)).toDense.asInstanceOf [VectorI])
        case SPARSE          => (SparseMatrixD (colVec), Vec.toInt (col(colPosV)).toDense.asInstanceOf [VectorI])
        case SYM_TRIDIAGONAL => (SymTriMatrixD (colVec), Vec.toInt (col(colPosV)).toDense.asInstanceOf [VectorI])
        case BIDIAGONAL      => (BidMatrixD (colVec),    Vec.toInt (col(colPosV)).toDense.asInstanceOf [VectorI])
        case COMPRESSED      => (RleMatrixD (colVec),    Vec.toInt (col(colPosV)).toDense.asInstanceOf [VectorI])
        } // match
    } // toMatriDI

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert 'this' relation into a matrix of integers.
     *  <p>
     *       in the regression equation: 'xb = y' create matrix 'xy'
     *  <p>
     *  @param colPos  the column positions to use for the matrix
     *  @param kind    the kind of matrix to create
     */
    def toMatriI (colPos: Seq [Int], kind: MatrixKind = DENSE): MatriI =
    {
        val colVec = for (x <- pi (colPos).col) yield Vec.toInt (x)
        kind match {
        case DENSE           => MatrixI (colVec)
        case SPARSE          => SparseMatrixI (colVec)
        case SYM_TRIDIAGONAL => SymTriMatrixI (colVec)
        case BIDIAGONAL      => BidMatrixI (colVec)
        case COMPRESSED      => RleMatrixI (colVec)
        } // match
    } // toMatriI

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert 'this' relation into a matrix of integers.  It will convert
     *  doubles and strings to integers.
     *  <p>
     *       in the regression equation: 'xb = y' create matrix 'xy'
     *  <p>
     *  @param colPos  the column positions to use for the matrix
     *  @param kind    the kind of matrix to create
     */
    def toMatriI2 (colPos: Seq [Int] = null, kind: MatrixKind = DENSE): MatriI =
    {
        import Converter._
        val cp = if (colPos == null) Seq.range(0, cols) else colPos
        val colVec = for (x <- pi (cp).col) yield {
            try {
                Vec.toInt (x)
            } catch {
                case num: NumberFormatException => mapToInt (x.asInstanceOf [VectorS])._1
            } // trys
        } // for
        kind match {
        case DENSE           => MatrixI (colVec)
        case SPARSE          => SparseMatrixI (colVec)
        case SYM_TRIDIAGONAL => SymTriMatrixI (colVec)
        case BIDIAGONAL      => BidMatrixI (colVec)
        case COMPRESSED      => RleMatrixI (colVec)
        } // match
    } // toMatriI2

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert 'this' relation into a matrix of integers and a vector of integers.
     *  <p>
     *       in the regression equation: 'xb = y' create matrix 'x' and vector 'y'
     *  <p>
     *  @param colPos   the column positions to use for the matrix
     *  @param colPosV  the column position to use for the vector
     *  @param kind     the kind of matrix to create
     */
    def toMatriII (colPos: Seq [Int], colPosV: Int, kind: MatrixKind = DENSE): Tuple2 [MatriI, VectorI] =
    {
        val colVec = for (x <- pi (colPos).col) yield Vec.toInt (x)
        kind match {
            case DENSE           => (MatrixI (colVec),       Vec.toInt (col(colPosV)).toDense.asInstanceOf [VectorI])
            case SPARSE          => (SparseMatrixI (colVec), Vec.toInt (col(colPosV)).toDense.asInstanceOf [VectorI])
            case SYM_TRIDIAGONAL => (SymTriMatrixI (colVec), Vec.toInt (col(colPosV)).toDense.asInstanceOf [VectorI])
            case BIDIAGONAL      => (BidMatrixI (colVec),    Vec.toInt (col(colPosV)).toDense.asInstanceOf [VectorI])
            case COMPRESSED      => (RleMatrixI (colVec),    Vec.toInt (col(colPosV)).toDense.asInstanceOf [VectorI])
        } // match
    } // toMatriII

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colPos' column of 'this' relation into a vector of doubles.
     *  @param colPos  the column position to use for the vector
     */
    def toVectorD (colPos: Int): VectorD = Vec.toDouble (col(colPos)).toDense.asInstanceOf [VectorD]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colName' column of 'this' relation into a vector of doubles.
     *  @param colName  the column name to use for the vector
     */
    def toVectorD (colName: String): VectorD = Vec.toDouble (col(colMap(colName))).toDense.asInstanceOf [VectorD]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colPos' column of 'this' relation into a vector of integers.
     *  @param colPos  the column position to use for the vector
     */
    def toVectorI (colPos: Int): VectorI = Vec.toInt (col(colPos)).toDense.asInstanceOf [VectorI]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colName' column of 'this' relation into a vector of integers.
     *  @param colName  the column name to use for the vector
     */
    def toVectorI (colName: String): VectorI = Vec.toInt (col(colMap(colName))).toDense.asInstanceOf [VectorI]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colPos' column of 'this' relation into a vector of integers.
     *  @param colPos  the column position to use for the vector
     */
    def toVectorS (colPos: Int): VectorS = col(colPos).asInstanceOf [VectorS]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colName' column of 'this' relation into a vector of integers.
     *  @param colName  the column name to use for the vector
     */
    def toVectorS (colName: String): VectorS = col(colMap(colName)).asInstanceOf [VectorS]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colPos' column of 'this' relation into a vector of doubles.
     *  @param colPos  the column position to use for the vector
     */
    def toRleVectorD (colPos: Int): RleVectorD = Vec.toDouble (col(colPos)).asInstanceOf [RleVectorD]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colName' column of 'this' relation into a vector of doubles.
     *  @param colName  the column name to use for the vector
     */
    def toRleVectorD (colName: String): RleVectorD = Vec.toDouble (col(colMap(colName))).asInstanceOf [RleVectorD]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colPos' column of 'this' relation into a vector of integers.
     *  @param colPos  the column position to use for the vector
     */
    def toRleVectorI (colPos: Int): RleVectorI = Vec.toInt (col(colPos)).asInstanceOf [RleVectorI]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colName' column of 'this' relation into a vector of integers.
     *  @param colName  the column name to use for the vector
     */
    def toRleVectorI (colName: String): RleVectorI = Vec.toInt (col(colMap(colName))).asInstanceOf [RleVectorI]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colPos' column of 'this' relation into a vector of integers.
     *  @param colPos  the column position to use for the vector
     */
    def toRleVectorS (colPos: Int): RleVectorS = col(colPos).asInstanceOf [RleVectorS]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the 'colName' column of 'this' relation into a vector of integers.
     *  @param colName  the column name to use for the vector
     */
    def toRleVectorS (colName: String): RleVectorS = col(colMap(colName)).asInstanceOf [RleVectorS]

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the given columns within 'this' relation to a map: 'keyColPos' -> 'valColPos'.
     *  @param keyColPos  the key column positions
     *  @param valColPos  the value column positions
     */
    def toMap (keyColPos: Seq [Int], valColPos: Int): Map [Seq [Any], Any] =
    {
        val map = Map [Seq [Any], Any] ()
        for (i <- indices) {
            val tuple = row(i)
            map += keyColPos.map (tuple(_)) -> tuple(valColPos)
        } // for
        map
    } // toMap

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Convert the given columns within 'this' relation to a map: 'keyColName' -> 'valColName'.
     *  @param keyColName  the key column names
     *  @param valColname  the value column names
     */
    def toMap (keyColName: Seq [String], valColName: String): Map [Seq [Any], Any] =
    {
        toMap (keyColName.map (colMap(_)), colMap(valColName))
    } // toMap

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Save 'this' relation in a file using serialization.
     */
    def save ()
    {
        val oos = new ObjectOutputStream (new FileOutputStream (STORE_DIR + name + SER))
        oos.writeObject (this)
        oos.close ()
    } // save

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Write 'this' relation into a CSV file with each row written to a line.
     *  @param fileName  the file name of the data file
     */
    def writeCSV (fileName: String)
    {
        val out = new PrintWriter (DATA_DIR + fileName)
        out.println (colName.toString.drop (5).dropRight (1))
        for (i <- 0 until rows) out.println (row(i).toString.drop (7).dropRight (1))
        out.close
    } // writeCSV

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Write 'this' relation into a JSON file.
     *  @param fileName  the file name of the data file
     */
    def writeJSON (fileName: String)
    {
        // FIX - to be implemented
    } // writeJSON

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    def min (cName: String) = Vec.min (col(colMap(cName)))

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    def max (cName: String) = Vec.max (col(colMap(cName)))

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    def sum (cName: String) = Vec.sum (col(colMap(cName)))

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    def mean (cName: String) = Vec.mean (col(colMap(cName)))

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    def count (cName: String) = rows

} // Relation class


//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RelationEx` object provides and example relation for testing.
 *  @see www.codeproject.com/Articles/652108/Create-First-Data-WareHouse
 */
object RelationEx
{
    val productSales = Relation ("productSales",
        Seq ("SalesInvoiceNumber", "SalesDateKey", "SalesTimeKey", "SalesTimeAltKey", "StoreID", "CustomerID",
             "ProductID", "SalesPersonID", "Quantity", "ProductActualCost", "SalesTotalCost", "Deviation"),
        Seq (Vector [Any] (1,  20130101, 44347, 121907, 1, 1, 1, 1, 2,  11.0,  13.0, 2.0),
             Vector [Any] (1,  20130101, 44347, 121907, 1, 1, 2, 1, 1,  22.5,  24.0, 1.5),
             Vector [Any] (1,  20130101, 44347, 121907, 1, 1, 3, 1, 1,  42.0,  43.5, 1.5),
             Vector [Any] (2,  20130101, 44519, 122159, 1, 2, 3, 1, 1,  42.0,  43.5, 1.5),
             Vector [Any] (2,  20130101, 44519, 122159, 1, 2, 4, 1, 3,  54.0,  60.0, 6.0),
             Vector [Any] (3,  20130101, 52415, 143335, 1, 3, 2, 2, 2,  11.0,  13.0, 2.0),
             Vector [Any] (3,  20130101, 52415, 143335, 1, 3, 3, 2, 1,  42.0,  43.5, 1.5),
             Vector [Any] (3,  20130101, 52415, 143335, 1, 3, 4, 2, 3,  54.0,  60.0, 6.0),
             Vector [Any] (3,  20130101, 52415, 143335, 1, 3, 5, 2, 1, 135.0, 139.0, 4.0),
             Vector [Any] (4,  20130102, 44347, 121907, 1, 1, 1, 1, 2,  11.0,  13.0, 2.0),
             Vector [Any] (4,  20130102, 44347, 121907, 1, 1, 2, 1, 1,  22.5,  24.0, 1.5),
             Vector [Any] (5,  20130102, 44519, 122159, 1, 2, 3, 1, 1,  42.0,  43.5, 1.5),
             Vector [Any] (5,  20130102, 44519, 122159, 1, 2, 4, 1, 3,  54.0,  60.0, 6.0),
             Vector [Any] (6,  20130102, 52415, 143335, 1, 3, 2, 2, 2,  11.0,  13.0, 2.0),
             Vector [Any] (6,  20130102, 52415, 143335, 1, 3, 5, 2, 1, 135.0, 139.0, 4.0),
             Vector [Any] (7,  20130102, 44347, 121907, 2, 1, 4, 3, 3,  54.0,  60.0, 6.0),
             Vector [Any] (7,  20130102, 44347, 121907, 2, 1, 5, 3, 1, 135.0, 139.0, 4.0),
             Vector [Any] (8,  20130103, 59326, 162846, 1, 1, 3, 1, 2,  84.0,  87.0, 3.0),
             Vector [Any] (8,  20130103, 59326, 162846, 1, 1, 4, 1, 3,  54.0,  60.0, 3.0),
             Vector [Any] (9,  20130103, 59349, 162909, 1, 2, 1, 1, 1,   5.5,   6.5, 1.0),
             Vector [Any] (9,  20130103, 59349, 162909, 1, 2, 2, 1, 1,  22.5,  24.0, 1.5),
             Vector [Any] (10, 20130103, 67390, 184310, 1, 3, 1, 2, 2,  11.0,  13.0, 2.0),
             Vector [Any] (10, 20130103, 67390, 184310, 1, 3, 4, 2, 3,  54.0,  60.0, 6.0),
             Vector [Any] (11, 20130103, 74877, 204757, 2, 1, 2, 3, 1,   5.5,   6.5, 1.0),
             Vector [Any] (11, 20130103, 74877, 204757, 2, 1, 3, 3, 1,  42.0,  43.5, 1.5)),
             0, "IIIIIIIIIDDD")

} // RelationEx object


//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RelationTest` object tests the operations provided by `Relation`.
 *  > runMain scalation.columnar_db.RelationTest
 */
object RelationTest extends App
{
    val weekdays = new Relation ("weekdays", Seq ("day", "time"),
                                 Vector (VectorS ("Mon", "Tue", "Wed", "Thu", "Fri"),
                                         VectorD (5.00, 8.15, 6.30, 9.45, 7.00)),
                                 0, "SD")

    val weekend = new Relation ("weekends", Seq ("day", "time"),
                                 Vector (VectorS ("Sat", "Sun"),
                                         VectorD (3.00, 4.30)),
                                 0, "SD")

    weekdays.generateIndex ()
    weekend.generateIndex ()

    banner ("weekdays")
    println ("weekdays                                  = " + weekdays)
    banner ("weekdend")
    println ("weekend                                   = " + weekend)

    banner ("Test pi")
    println ("weekdays.pi (\"day\")                     = " + weekdays.pi ("day"))
    println ("-" * 60)
    println ("weekdays.pisigmaS (\"day\", _ == \"Mon\") = " + weekdays.pisigmaS ("day", _ == "Mon"))

    banner ("Test sigma")
    println ("weekdays.sigmaS (\"day\", _ == \"Mon\")   = " + weekdays.sigmaS ("day", _ == "Mon"))
    println ("-" * 60)
    println ("weekdays.sigma (\"day\", _ == \"Mon\")    = " + weekdays.sigma ("day", (x: StrNum) => x == "Mon"))
    println ("-" * 60)
    println ("weekdays.sigma (\"time\", _ == 5.00)      = " + weekdays.sigma ("time", (x: Double) => x == 5.00))
    println ("-" * 60)
    println ("weekdays.sigmaS (\"day\", _ > \"Mon\")    = " + weekdays.sigmaS ("day", _ > "Mon"))
    println ("-" * 60)
    println ("weekdays.selectS (\"day\", _ > \"Mon\")   = " + weekdays.selectS ("day", _ > "Mon"))
    println ("-" * 60)
    println ("weekdays.sigmaSD (\"day\", \"time\")      = " + weekdays.sigmaS ("day",  _ == "Mon"))

    val week = weekdays.union (weekend)
    banner ("Test union")
    println ("weekdays.union (weekend)                  = " + week)

    weekend.add (Vector ("Zday", 1.00))
    banner ("Test add")
    println ("weekend add (\"Zday\", 1.00))             = " + weekend)

    banner ("Test -")
    println ("week - weekend                            = " + (week - weekend))

    banner ("Test join")
    println ("week.join (\"day\", \"day\" weekend)      = " + week.join ("day", "day", weekend))
    println ("-" * 60)
    println ("week >< weekend                           = " + (week >< weekend))

    week.writeCSV ("columnar_db" + ⁄ + "week.csv")

} // RelationTest object


//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RelationTest2` object tests the operations provided by `Relation`.
 *  The relational algebra operators are given using Unicode.
 *  @see en.wikipedia.org/wiki/List_of_Unicode_characters
 *  > runMain scalation.columnar_db.RelationTest2
 */
object RelationTest2 extends App
{
    val weekdays = new Relation ("weekdays", Seq ("day", "time"),
                                 Vector (VectorS ("Mon", "Tue", "Wed", "Thu", "Fri"),
                                         VectorD (5.00, 8.15, 6.30, 9.45, 7.00)),
                                 0, "SD")

    val weekend = new Relation ("weekends", Seq ("day", "time"),
                                Vector (VectorS ("Sat", "Sun"),
                                        VectorD (3.00, 4.30)),
                                0, "SD")

    banner ("Test π")
    println ("weekdays.π (\"day\")               = " + weekdays.π ("day"))
    println ("-" * 60)
    println ("weekdays.π (\"time\")              = " + weekdays.π ("time"))

    banner ("Test σ")
    println ("weekdays.σ (\"day\", _ == \"Mon\") = " + weekdays.σ ("day", (x: StrNum) => x == "Mon"))
    println ("-" * 60)
    println ("weekdays.σ (\"time\", _ == 5.00)   = " + weekdays.σ ("time", (x: Double) => x == 5.00))
    println ("-" * 60)
    println ("weekdays.σ (\"day\", _ > \"Mon\")  = " + weekdays.σ ("day", (x: StrNum) => x > "Mon"))
    println ("-" * 60)
    println ("weekdays.σ (\"time\", _ > 5.00)    = " + weekdays.σ ("time", (x: Double) => x > 5.00))
    println ("-" * 60)
    println ("weekdays.σ (\"day\", \"time\")     = " + weekdays.σ ("day",  (x: StrNum) => x == "Mon")
                                                               .σ ("time", (x: Double) => x == 5.00))
    val week = weekdays ⋃ weekend

    banner ("Test ⋃")
    println ("weekdays ⋃ weekend)                = " + weekdays ⋃ weekend)

    banner ("Test ⋂")
    println ("week ⋂ weekend                     = " + (week ⋂ weekend))

    banner ("Test -")
    println ("week - weekend                     = " + (week - weekend))

    banner ("Test ⋈ ")
    println ("week ⋈ weekend                     = " + (week ⋈ weekend))

} // RelationTest2 object


//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RelationTest3` object tests the operations provided by `Relation`.
 *  It test various aggregate/OLAP operations on a simple data warehouse fact table.
 *  @see www.codeproject.com/Articles/652108/Create-First-Data-WareHouse
 *  FIX - allow entering doubles as "13" rather than "13.0"
 *  > runMain scalation.columnar_db.RelationTest3
 */
object RelationTest3 extends App
{
    import Relation.{max, min}
    import RelationEx.productSales

    val costVprice = productSales.π ("ProductActualCost", "SalesTotalCost")

    productSales.show ()

    println ("productSales = " + productSales)
    println ("productSales.π (\"ProductActualCost\", \"SalesTotalCost\") = " + costVprice)

    banner ("Test count")
    println ("count (productSales) = " + count (productSales))
    println ("-" * 60)
    println ("count (costVprice)   = " + count (costVprice))

    banner ("Test min")
    println ("min (productSales)   = " + min (productSales))
    println ("-" * 60)
    println ("min (costVprice)     = " + min (costVprice))

    banner ("Test max")
    println ("max (productSales)   = " + max (productSales))
    println ("-" * 60)
    println ("max (costVprice)     = " + max (costVprice))

    banner ("Test sum")
    println ("sum (productSales)   = " + sum (productSales))
    println ("-" * 60)
    println ("sum (costVprice)     = " + sum (costVprice))

    banner ("Test expectation/mean")
    println ("Ɛ (productSales)     = " + Ɛ (productSales))
    println ("-" * 60)
    println ("Ɛ (costVprice)       = " + Ɛ (costVprice))

    banner ("Test variance")
    println ("Ʋ (productSales)     = " + Ʋ (productSales))
    println ("-" * 60)
    println ("Ʋ (costVprice)       = " + Ʋ (costVprice))

    banner ("Test correlation")
    println ("corr (costVprice)    = " + corr (costVprice))

} // RelationTest3 object


//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RelationTest4` object tests conversion `Relation` to a matrix.
 *  > runMain scalation.columnar_db.RelationTest4
 */
object RelationTest4 extends App
{
    import RelationEx.productSales

    val (mat, vec) = productSales.toMatriDD (0 to 10, 11)

    banner ("productSales")
    productSales.show ()

    banner ("mat and vec")
    println ("mat = " + mat)
    println ("vec = " + vec)

} // RelationTest4 object


//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RelationTest5` object tests the interoperability between Relations and Matrices.
 *  > runMain scalation.columnar_db.RelationTest5
 */
object RelationTest5 extends App
{
    val sales_item1 =  Relation ("Sales_Item1", Seq ("Date", "FL", "GA", "NC", "SC"),
        Seq (Vector [Any] ("20130101", 10, 5, 5, 4),
             Vector [Any] ("20130102", 20, 30, 40, 25),
             Vector [Any] ("20130103", 8, 6, 9, 9),
             Vector [Any] ("20130104", 6, 7, 9, 10),
             Vector [Any] ("20130105", 4, 7, 9, 10)),
        0,"SIIII")

    val price_item1 =  Relation ("Price_Item1", Seq ("Date", "FL", "GA", "NC", "SC"),
        Seq (Vector [Any] ("20130101", 1.6, 1.6, 1.5, 1.3),
             Vector [Any] ("20130102", 1.6, 1.6, 1.5, 1.2),
             Vector [Any] ("20130103", 1.5, 1.6, 1.5, 1.4),
             Vector [Any] ("20130104", 1.4, 1.7, 1.5, 1.4),
             Vector [Any] ("20130105", 1.4, 1.7, 1.4, 1.4)),
        0,"SDDDD")
    val revenue     =  Relation ("Revenue", -1, null, "Item", "FL", "GA", "NC", "SC")

    sales_item1.show ()
    price_item1.show ()

    val x = sales_item1.toMatriD (1 to 4, COMPRESSED)
    val y = price_item1.toMatriD (1 to 4, COMPRESSED)
    val z = x dot y
    revenue.add ("Item1" +: z().toVector)

    banner ("revenue")
    revenue.show ()

} // RelationTest5


//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RelationTest6` object tests 'indexjoin', 'parjoin', 'groupby' and 'aggregation'.
 *  > runMain scalation.columnar_db.RelationTest6
 */
object RelationTest6 extends App
{
    val professor = Relation ("professor", 0, "ISS", "pid", "name", "prodeptid")
    TableGen.popTable (professor, 10)
    professor.generateIndex ()

    val course = Relation ("course", 0, "ISS", "cid","crsname", "descr")
    TableGen.popTable (course, 20)
    course.generateIndex ()

    val teaching = Relation ("teaching", 0, "IISI", "tid", "cid", "semester", "pid")
    teaching.fKeys = Seq (("cid", "course", 0), ("pid", "professor", 0))
    TableGen.popTable (teaching, 50, Seq (course, professor))
    teaching.generateIndex ()

    banner ("database")
    professor.show ()
    course.show ()
    teaching.show ()
    teaching.showFk ()

    banner ("joinindex")
    teaching.joinindex (Seq("pid"), Seq("pid"), professor).show ()
    banner ("parjoin")
    teaching.parjoin (Seq("pid"), Seq("pid"), professor, 4).show ()
    banner ("groupBy")
    teaching.groupBy ("cid").epi (Seq (count2), Seq("count"), Seq ("pid"), "tid", "semester").show
    banner ("join ... on")
    teaching.join (professor).on [Int] ("pid", "pid", _ ==_).show

} // RelationTest6


//::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RelationTest7` object tests 'thetajoin' and 'where' methods.
 *  > runMain scalation.columnar_db.RelationTest7
 */
object RelationTest7 extends App
{
    val professor = Relation ("professor",
        Seq("pid", "name", "department", "title"),
        Seq (Vector [Any] (1, "jackson", "pharm", 4),
             Vector [Any] (2, "ken", "cs", 2),
             Vector [Any] (3, "pan", "pharm", 0),
             Vector [Any] (4, "yang", "gis", 3),
             Vector [Any] (5, "zhang", "cs", 0),
             Vector [Any] (6, "Yu", "cs", 0)),
        -1, "ISSI")

    val professor2 = Relation ("professor",
        Seq ("pid", "name", "department", "title"),
        Seq (Vector [Any] (7, "LiLy", "gis", 5),
             Vector [Any] (8, "Marry", "gis", 5),
             Vector [Any] (0, "Kate", "cs", 5)),
        0, "ISSI")

    professor.generateIndex ()
    professor2.generateIndex ()

    banner ("professor")
    professor.show ()
    banner ("professor2")
    professor2.show ()

    banner ("thetajoin")
    professor.thetajoin [Int] (professor2,("pid", "pid", (x, y) => x < y)).show ()
//  professor.where (("pid", (x: Int) => x == 6)).show ()
    banner ("where")
    professor.where [Int] (("pid", _ == 6)).show ()
    banner ("where")
    professor.where (("department", (x: StrNum) => x == "cs"),("pid", (x: Int) => x < 6)).show ()

} // RelationTest7

