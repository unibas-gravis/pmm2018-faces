package summerschool.faceproject.debugging

package debugging

import java.io.File

import breeze.plot._

import scala.io.Source

object PlotVerboseDebugOutput extends App {

  // read lines and split at spaces
  val source = Source.fromFile(new File("results/verboseLogger.txt"))
  val allLines = source.getLines().toIndexedSeq
  val infos = allLines.map(line => line.split(' '))

  // plot ar-ratios per proposal
  {
    val proposalNames = infos.map(_(2)).distinct // find name of logged proposals

    // evaluate the ar-ratio batch wise
    val ratioHistory = infos.grouped(400).toIndexedSeq.map { lines =>

      // evalute the ar-ratio of each proposal
      val ratios = for (name <- proposalNames) yield {

        val selectedProposals = lines.filter(info => info(2).contains(name))
        val statusSeq = selectedProposals.map(_(1))

        val nA = statusSeq.count(_=="A")
        val nR = statusSeq.count(_=="R")
        val N = nA + nR
        val ratio = nA.toDouble / N

        // pretty print info
        val d = (ratio * 100).toInt
        print(s"${name} ${"%3d".format(d)}% / (${"%3d".format(N)}) \t|\t ")
        ratio
      }
      println()
      ratios
    }


    // make the ar-plot
    val f = Figure("AR-Plot")
    val p = f.subplot(0)
    val linspace = (0 until ratioHistory.size).map(_.toDouble)
    for( (selector,idx) <- proposalNames.zipWithIndex ) {
      val y = ratioHistory.map(seq => seq(idx))
      p += plot(linspace, y, name=selector)
    }
    p.legend = true
    p.ylim = (-0.05,1.05)
    f.refresh()
  }



  // plot p-values for accepted and rejected samples separately
  {

    // get ((status, p-value) index) of samples
    val statusPValueIndex = infos.map(i => (i(1),i(3).toDouble)).zipWithIndex

    // divide accepted and rejected samples apart → (index,pvalue)
    val values = for (status <- Seq("A", "R")) yield {
      val setOfSamples = statusPValueIndex.filter(smp => smp._1._1 == status)
      setOfSamples.map{ case (info,index) =>
        (index,info._2)
      }
    }

    // plot values for the two sequences
    val f = Figure("neg. log. p-values")
    val p = f.subplot(0)
    for ( valueSequence <- values ) {
      val (x,y) = valueSequence.unzip
      p += plot(x.map(_.toDouble),y,'.')
    }
    f.refresh()

  }
}
