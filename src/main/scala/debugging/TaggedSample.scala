package debugging

/*
 * Copyright University of Basel, Graphics and Vision Research Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.{OutputStream, PrintWriter}

import scalismo.sampling.loggers.{AcceptRejectLogger, ChainStateLogger}
import scalismo.sampling.{DistributionEvaluator, ProposalGenerator, TransitionProbability}



case class TaggedSample[A](tag: String, sample: A)

case class TaggedProposalGenerator[A](
                                       name: String,
                                       generator: ProposalGenerator[A] with TransitionProbability[A]
                                     ) extends ProposalGenerator[TaggedSample[A]] with TransitionProbability[TaggedSample[A]] {

  override def propose(current: TaggedSample[A]): TaggedSample[A] = {
    TaggedSample(name,generator.propose(current.sample))
  }

  override def logTransitionProbability(from: TaggedSample[A], to: TaggedSample[A]): Double = {
    generator.logTransitionProbability(from.sample,to.sample)
  }
}

case class TaggedSampleVerboseLogger[A](ostream: OutputStream) extends AcceptRejectLogger[TaggedSample[A]] {

  var index = 0
  val writer = new PrintWriter(ostream)

  override def accept(current: TaggedSample[A], sample: TaggedSample[A], generator: ProposalGenerator[TaggedSample[A]], evaluator: DistributionEvaluator[TaggedSample[A]]): Unit = {
    log("A",current,sample,generator,evaluator)
    index += 1
  }

  override def reject(current: TaggedSample[A], sample: TaggedSample[A], generator: ProposalGenerator[TaggedSample[A]], evaluator: DistributionEvaluator[TaggedSample[A]]): Unit = {
    log("R",current,sample,generator,evaluator)
    index += 1
  }

  def log(tag: String, current: TaggedSample[A], sample: TaggedSample[A], generator: ProposalGenerator[TaggedSample[A]], evaluator: DistributionEvaluator[TaggedSample[A]]): Unit = {
    writer.println(s"${"%06d".format(index)} $tag ${sample.tag} ${evaluator.logValue(sample)}")
    writer.flush()
  }

  def stop(): Unit = {
    writer.flush()
    writer.close()
  }
}

object TaggedSample {

  object implicits {

    implicit class ProposalGeneratorTagger[A](val generator: ProposalGenerator[A] with TransitionProbability[A]) {
      def withName(name: String) = TaggedProposalGenerator(name,generator)
    }

    implicit class TaggedDistributionEvaluator[A](val evaluator: DistributionEvaluator[A]) extends DistributionEvaluator[TaggedSample[A]]{
      override def logValue(namedSample: TaggedSample[A]): Double = {
        evaluator.logValue(namedSample.sample)
      }
    }

    implicit class TaggedAcceptRejectLogger[A](val logger: AcceptRejectLogger[A]) extends AcceptRejectLogger[TaggedSample[A]] {
      override def accept(current: TaggedSample[A], sample: TaggedSample[A], generator: ProposalGenerator[TaggedSample[A]], evaluator: DistributionEvaluator[TaggedSample[A]]): Unit = {
        logger.accept(current.sample,sample.sample,generator.asInstanceOf[TaggedProposalGenerator[A]].generator,evaluator.asInstanceOf[TaggedDistributionEvaluator[A]].evaluator)
      }

      override def reject(current: TaggedSample[A], sample: TaggedSample[A], generator: ProposalGenerator[TaggedSample[A]], evaluator: DistributionEvaluator[TaggedSample[A]]): Unit = {
        logger.reject(current.sample,sample.sample,generator.asInstanceOf[TaggedProposalGenerator[A]].generator,evaluator.asInstanceOf[TaggedDistributionEvaluator[A]].evaluator)
      }
    }

    implicit class TaggedChainStateLogger[A](val logger: ChainStateLogger[A]) extends ChainStateLogger[TaggedSample[A]] {
      override def logState(sample: TaggedSample[A]): Unit = {
        logger.logState(sample.sample)
      }
    }

  }

}
