package org.jetbrains.plugins.scala.testingSupport.scalatest.singleTest

import org.jetbrains.plugins.scala.testingSupport.scalatest.generators.FreeSpecGenerator

/**
 * @author Roman.Shein
 * @since 20.01.2015.
 */
trait FreeSpecSingleTestTest extends FreeSpecGenerator {
  def testFreeSpec() {
    addFreeSpec()

    runTestByLocation(6, 3, "FreeSpecTest.scala",
      checkConfigAndSettings(_, "FreeSpecTest", "A FreeSpecTest should be able to run single tests"),
      root => checkResultTreeHasExactNamedPath(root, "[root]", "FreeSpecTest", "A FreeSpecTest", "should be able to run single tests") &&
          checkResultTreeDoesNotHaveNodes(root, "should not run tests that are not selected"),
      debug = true
    )
  }
}
