
//:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** @author  John Miller
 *  @version 1.3
 *  @date    Sat Mar 22 14:39:30 EDT 2014
 *  @see     LICENSE (MIT style license file).
 */

package scalation.random

import scala.math.floor

import scalation.util.{Error, time}

//:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RNG` abstract class is the base class for all ScalaTion Random Number
 *  Generators.  The subclasses must implement a 'gen' method that generates
 *  random real numbers in the range (0, 1).  They must also implement an 'igen'
 *  methods to return stream values.
 *  @param stream  the random number stream index
 */
abstract class RNG (stream: Int)
         extends Error
{
    if (stream < 0 || stream >= RandomSeeds.seeds.length) {
        flaw ("constructor", "the stream must be in the range 0 to " + (RandomSeeds.seeds.length - 1))
    } // if

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the theoretical mean for the random number generator's 'gen' method.
     */
    val mean = 0.5

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Compute the probability function (pf), i.e., the probability density
     *  function (pdf).
     *  @param z  the mass point whose probability density is sought
     */
    def pf (z: Double): Double = if (0.0 <= z && z <= 1.0) 1.0 else 0.0

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the next random number as a real `Double` in the interval (0, 1).
     */
    def gen: Double

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return the next stream value as an integer `Int`.
     */
    def igen: Int

} // RNG class


//:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RNGStream` object allows for random selection of streams for applications
 *  where reproducibility of random numbers is not desired.
 */
object RNGStream
{
    /** Use Java's random number generator to randomly select one of ScalaTion's
     *  random number streams:  0 until `RandomSeeds`.seeds.length
     *  "If you use the nullary constructor, new Random(), then 'System.currentTimeMillis'
     *  will be used for the seed, which is good enough for almost all cases."
     *  @see stackoverflow.com/questions/22530702/what-is-seed-in-util-random
     *  @see docs.oracle.com/javase/8/docs/api/index.html
     */
    private val javaRNG = new java.util.Random ()

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Return a randomly selected random number stream.
     */
    def ranStream: Int = javaRNG.nextInt (RandomSeeds.seeds.length)

} // RNGStream object


//:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `RNGTest` object conducts three simple tests of the Random Number
 *  Generators: (1) Speed Test, (2) Means Test and (3) Chi-square Goodness of Fit Test.
 *  FIX: need to add (3) Variance Test and (4) K-S Goodness of Fit Test.
 *  > run-main scalation.random.RNGTest
 */
object RNGTest extends App with Error
{
    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Perform a Means Test (average of generated rn's close to mean for distribution).
     *  @param rn  the random number generator to test
     */
    def meansTest (rn: RNG)
    {
        println ("\nTest the `" + rn.getClass.getSimpleName () + "` random number generator")

        val tries = 5
        val reps  = 10000000
        var sum   = 0.0
        for (i <- 0 until tries) {
            time {  for (i <- 0 until reps) sum += rn.gen }
            println ("gen: sum = " + sum)
            println ("rn.mean = " + rn.mean + " estimate = " + sum / reps.toDouble)
            sum = 0.0
        } // for
    } // meansTest

    //:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Perform a Chi-square Goodness of Fit Test.  Compare the random number's
     *  histogram (generated by repeatedly calling 'gen') to the probability
     *  function pf (pdf).
     *  @param rn  the random number generator to test
     */
    def distrTest (rn: RNG)
    {
        println ("\nTest the " + rn.getClass.getSimpleName () + " random number generator")

        val nints = 50                                         // number of intervals
        val reps  = 1000000                                    // number of replications
        val e     = reps / nints                               // expected value: pf (x)

        val sum   = Array.ofDim [Int] (nints)
        for (i <- 0 until reps) {
            val j = floor (rn.gen * nints).toInt               // interval number
            if (0 <= j && j < nints) sum (j) += 1
        } // for

        var chi2 = 0.0                                         // sum up for Chi-square statistic
        for (i <- sum.indices) {
            val o = sum(i)                                     // observed value: height of histogram
            chi2 += (o - e)*(o - e) / e
            print ("\tsum (" + i + ") = " + o + " : " + e + " ")
            if (i % 5 == 4) println ()
        } // for

        var n = nints - 1                                      // degrees of freedom
        if (n < 2)  flaw ("distrTest", "use more intervals to increase the degrees of freedom")
        if (n > 49) n = 49
        println ("\nchi2 = " + chi2 + " : chi2(0.95, " + n + ") = " + Quantile.chiSquareInv (0.95, n))
    } // distrTest

    val generators = Array (Random (), Random2 (), Random3 ())

    for (g <- generators) {
       meansTest (g)
       distrTest (g)
    } // for

} // RNGTest object

