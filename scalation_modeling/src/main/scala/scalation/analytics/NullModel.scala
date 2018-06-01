
//:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** @author  John Miller
 *  @version 1.5
 *  @date    Fri Jan  5 14:03:36 EST 2018
 *  @see     LICENSE (MIT style license file).
 */

package scalation.analytics

import scalation.linalgebra.{MatriD, MatrixD, VectoD, VectorD}
import scalation.math.double_exp
import scalation.plot.Plot
import scalation.util.Error

//:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `NullModel` class implements the simplest type of predictive modeling technique
 *  that just predicts the response 'y' to be the mean.
 *  Fit the parameter vector 'b' in the regression equation
 *  <p>
 *      y  =  b dot x + e  =  b0 + e
 *  <p>
 *  where 'e' represents the residuals (the part not explained by the model).
 *  @param y  the response vector
 */
class NullModel (y: VectoD)
      extends Fit (y, 1, (1, y.dim)) with Predictor with Error
{
    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Train the predictor by fitting the parameter vector (b-vector) in the
     *  simpler regression equation.
     *  @param yy  the response vector
     */
    def train (yy: VectoD = y): NullModel =
    {
        b = VectorD (y.mean)                              // parameter vector [b0]
        this
    } // train

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Compute the error and useful diagnostics.
     */
    def eval ()
    {
        e = y - b(0)                                      // compute residual/error vector e
        diagnose (e)                                      // compute diagnostics
    } // eval

    //::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    /** Predict the value of 'y = f(z)' by evaluating the formula 'y = b dot z',
     *  i.e., '[b0] dot [z0]'.
     *  @param z  the new vector to predict
     */
    def predict (z: VectoD): Double = b(0)

} // NullModel class


//:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `NullModelTest` object is used to test the `NullModel` class.
 *  <p>
 *      y = b dot x + e = b0 + e
 *  <p>
 *  > runMain scalation.analytics.NullModelTest
 */
object NullModelTest extends App
{
    // 4 data points:
    val y = VectorD (1, 3, 3, 4)                        // y vector

    println ("y = " + y)

    val rg = new NullModel (y)
    rg.train ().eval ()

    println ("coefficient = " + rg.coefficient)
    println ("fitMap      = " + rg.fitMap)

    val z  = VectorD (5)                                // predict y for one point
    val yp = rg.predict (z)
    println ("predict (" + z + ") = " + yp)

} // NullModelTest object


//:::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
/** The `NullModelTest2` object is used to test the `NullModel` class.
 *  <p>
 *      y = b dot x + e = b0 + e
 *  <p>
 *  > runMain scalation.analytics.NullModelTest2
 */
object NullModelTest2 extends App
{
    // 5 data points:
    val y = VectorD (2.0, 3.0, 5.0, 4.0, 6.0)           // y vector

    println ("y = " + y)

    val rg = new NullModel (y)
    rg.train ().eval ()

    println ("coefficient = " + rg.coefficient)
    println ("fitMap      = " + rg.fitMap)

    val z  = VectorD (5.0)                              // predict y for one point
    val yp = rg.predict (z)
    println ("predict (" + z + ") = " + yp)

} // NullModelTest2 object

