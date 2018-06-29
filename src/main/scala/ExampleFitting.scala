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

package summerschool

import java.io.File
import javax.swing.JLabel

import scalismo.faces.color.{RGB, RGBA}
import scalismo.faces.deluminate.SphericalHarmonicsOptimizer
import scalismo.faces.gui.ImagePanel
import scalismo.faces.image.PixelImage
import scalismo.faces.io.{MoMoIO, PixelImageIO, RenderParameterIO, TLMSLandmarksIO}
import scalismo.faces.landmarks.LandmarksDrawer
import scalismo.faces.mesh.MeshSurfaceSampling
import scalismo.faces.momo.MoMo
import scalismo.faces.parameters.{ImageSize, MoMoInstance, RenderParameter}
import scalismo.faces.sampling.face.MoMoRenderer
import scalismo.faces.sampling.face.evaluators.PixelEvaluators.{ConstantPixelEvaluator, IsotropicGaussianPixelEvaluator}
import scalismo.faces.sampling.face.evaluators.PriorEvaluators.{GaussianShapePrior, GaussianTexturePrior}
import scalismo.faces.sampling.face.evaluators._
import scalismo.faces.sampling.face.proposals._
import scalismo.faces.sampling.face.proposals.SphericalHarmonicsLightProposals.SHLightSolverProposal
import scalismo.geometry.{Vector, Vector3D}
import scalismo.mesh.TriangleMesh3D
import scalismo.sampling.DistributionEvaluator
import scalismo.sampling.algorithms.MetropolisHastings
import scalismo.sampling.evaluators.ProductEvaluator
import scalismo.sampling.loggers.{BestSampleLogger, ChainStateLogger, ChainStateLoggerContainer}
import scalismo.sampling.proposals.{CorrectedMetropolisFilterProposal, MetropolisFilterProposal, MixtureProposal}
import scalismo.utils.Random




//==== IMPORTANT IMPORTS FOR WHICH THE IDE USUALLY DOES NOT PROVIDE ANY HELP
import scalismo.faces.gui.GUIBlock._
import scalismo.faces.sampling.evaluators.CachedDistributionEvaluator.implicits._
import scalismo.faces.sampling.face.proposals.ParameterProposals.implicits._
import scalismo.sampling.evaluators.ProductEvaluator.implicits._
import scalismo.sampling.loggers.ChainStateLogger.implicits._
import scalismo.sampling.proposals.MixtureProposal.implicits._



object ExampleFitting extends App {

  //=== SETUP DATA, RENDERER AND PARAMETERS =======

  scalismo.initialize()
  implicit val rng = Random(1024L)

  // the target
  val id = "target"
  val scaling = 0.5

  // load image and scale
  val targetFull = PixelImageIO.read[RGBA](new File(s"data/challenge/${id}.png")).get
  val target = targetFull.resample(
    (targetFull.width*scaling).toInt,
    (targetFull.height*scaling).toInt
  )

  // load landmarks and scale
  val landmarksFull = TLMSLandmarksIO.read2D(new File(s"data/challenge/${id}.tlms")).get
  val landmarksFromFile = landmarksFull.map{ lm =>
    val newLocation = (lm.point.toVector*scaling).toPoint
    lm.copy(point = newLocation)
  }

  // the model
  val modelId = "model2015-1_l6_face12_nomouth_pca"
  val modelFile: File = new File(s"../summerschool/data/${modelId}.h5")
  val modelFull: MoMo = MoMoIO.read(modelFile).get

  // reduce number of used components
  val rank = 50
  val model: MoMo = modelFull.neutralModel.truncate(rank,rank)

  // renderer & initial parameters
  val renderer: MoMoRenderer = MoMoRenderer(model).cached(20)
  val momoInstance = MoMoInstance.zero(rank,rank,0,modelFile.toURI)
  val initial = RenderParameter.defaultSquare.
    withImageSize(ImageSize(target.width,target.height)).
    withMoMo(momoInstance)


  // use only landmarks in the file and in the model
  val landmarks = landmarksFromFile.filter(lm => model.landmarks.contains(lm.id))
  val landmarkTags = landmarks.map(lm => lm.id)




  //=== PROPOSALS =======


  // model proposal
  val shapeProposal = MixtureProposal(
    GaussianMoMoShapeProposal(0.1)+
      GaussianMoMoShapeProposal(0.05)+
      GaussianMoMoShapeProposal(0.01)+
      GaussianMoMoShapeCaricatureProposal(0.1f)
  ).toParameterProposal

  val colorProposal = MixtureProposal(
    GaussianMoMoColorProposal(0.1)+
      GaussianMoMoColorProposal(0.05)+
      GaussianMoMoColorProposal(0.01)+
      GaussianMoMoColorCaricatureProposal(0.1f)
  ).toParameterProposal


  // light propsals
  val illuGaussProposal = MixtureProposal(
    SphericalHarmonicsLightProposals.SHLightPerturbationProposal(0.01,true).toParameterProposal+
      SphericalHarmonicsLightProposals.SHLightPerturbationProposal(0.001,true).toParameterProposal+
      SphericalHarmonicsLightProposals.SHLightPerturbationProposal(0.01,false).toParameterProposal+
      SphericalHarmonicsLightProposals.SHLightPerturbationProposal(0.001,false).toParameterProposal
  )

  val shlOptimizer = SphericalHarmonicsOptimizer(renderer,target)
  val samplingStrategy = (mesh: TriangleMesh3D) => MeshSurfaceSampling.sampleUniformlyOnSurface(1000)(mesh)
  val illuOptiProposal = SHLightSolverProposal(shlOptimizer,samplingStrategy).toParameterProposal

  val illuminationProposal = MixtureProposal(0.01*:illuOptiProposal + 0.9*:illuGaussProposal)


  // rotation proposals
  val rollProposal = MixtureProposal(
    GaussianRotationProposal(Vector3D.unitZ,0.01) +
    GaussianRotationProposal(Vector3D.unitZ,0.005) +
    GaussianRotationProposal(Vector3D.unitZ,0.0005)
  )
  val yawProposal = MixtureProposal(
    GaussianRotationProposal(Vector3D.unitY,0.01)+
    GaussianRotationProposal(Vector3D.unitY,0.005)+
    GaussianRotationProposal(Vector3D.unitY,0.0005)
  )
  val pitchProposal = MixtureProposal(
    GaussianRotationProposal(Vector3D.unitX,0.01)+
    GaussianRotationProposal(Vector3D.unitX,0.005)+
    GaussianRotationProposal(Vector3D.unitX,0.0005)
  )

  val rotationProposal = MixtureProposal(rollProposal+yawProposal+pitchProposal).toParameterProposal


  // distance to camera proposals - compensate for the scaling of the face in the image
  val distanceProposalC = GaussianDistanceProposal(500f, compensateScaling = true).toParameterProposal
  val distanceProposalF = GaussianDistanceProposal(50f, compensateScaling = true).toParameterProposal
  val distanceProposalHF = GaussianDistanceProposal(5f, compensateScaling = true).toParameterProposal
  val distanceProposal = MixtureProposal(0.2 *: distanceProposalC + 0.6 *: distanceProposalF + 0.2 *: distanceProposalHF)


  // scaling proposals - these can change the size of the face in the image
  val scalingProposalC = GaussianScalingProposal(0.15f).toParameterProposal
  val scalingProposalF = GaussianScalingProposal(0.05f).toParameterProposal
  val scalingProposalHF = GaussianScalingProposal(0.01f).toParameterProposal
  val scalingProposal = MixtureProposal(0.2 *: scalingProposalC + 0.6 *: scalingProposalF + 0.2 *: scalingProposalHF)


  // correct pose proposals for shifts of landmark in the image - one lm position is kept fix
  val centerREyeProposal = ImageCenteredProposal( MixtureProposal(rotationProposal+distanceProposal+scalingProposal),
    "right.eye.corner_outer", renderer).get

  val centerLEyeProposal = ImageCenteredProposal( MixtureProposal(rotationProposal+distanceProposal+scalingProposal),
    "left.eye.corner_outer", renderer).get

  val centerRLipsProposal = ImageCenteredProposal( MixtureProposal(rotationProposal+distanceProposal+scalingProposal),
    "right.lips.corner", renderer).get

  val centerLLipsProposal = ImageCenteredProposal( MixtureProposal(rotationProposal+distanceProposal+scalingProposal),
    "left.lips.corner", renderer).get


  // translation proposals XY-plane
  val translationProposal = MixtureProposal(
    GaussianTranslationProposal(Vector(5.0,5.0))+
      GaussianTranslationProposal(Vector(0.1,0.1))+
      GaussianTranslationProposal(Vector(0.01,0.01))
  ).toParameterProposal

  val poseProposal = MixtureProposal(centerREyeProposal + centerLEyeProposal + centerRLipsProposal + centerLLipsProposal + translationProposal)



  // combined proposals
  val landmarkFittingProposal = MixtureProposal(
    0.4*:poseProposal+
    0.6*:shapeProposal
  )

  val fullImageFittingProposal = MixtureProposal(
    1.0*:poseProposal+
    2.0*:shapeProposal+
    2.0*:colorProposal+
    1.0*:illuminationProposal
  )





  //=== EVALUATORS =======

  // model prior evaluator
  val texturePrior = GaussianTexturePrior(0.0,1.0)
  val shapePrior = GaussianShapePrior(0.0,1.0)
  val modelPrior: DistributionEvaluator[RenderParameter] = ProductEvaluator( texturePrior * shapePrior)


  // landmarks evaluator
  val landmarksEval = LandmarkPointEvaluator.isotropicGaussian(
    landmarks, // observed target landmarks
    3.0,       // uncertainty of each landmark
    renderer   // renderer used to generate the landmark positions
  )


  // image evaluator - values can be found in publications
  val colSigma = 0.043 // bg ~ 0.13
  val foregroundEval = IsotropicGaussianPixelEvaluator(colSigma)
  val backgroundEval = ConstantPixelEvaluator[RGB](foregroundEval.logValue(RGB.Black,RGB(2*colSigma/Math.sqrt(3.0))))
  val ipEval = IndependentPixelEvaluator(target,foregroundEval,backgroundEval)

  // use collective likelihood instead of independent pixel evaluation
//  val ipEval = CollectiveLikelihoodEvaluator(0.072,9.0).toDistributionEvaluator(target)

  val imageEval = ImageRendererEvaluator(renderer,ipEval)


  // combined evaluator
  val landmarkFittingEvaluator = ProductEvaluator(landmarksEval * modelPrior).cached(20)
  val fullImageFittingEvaluator: DistributionEvaluator[RenderParameter] = ProductEvaluator(imageEval * landmarksEval * modelPrior).cached(20)






  //=== LOGGING =======

  // UI
  val targetPanel = ImagePanel(target)
  val imagePanel = ImagePanel(target)
  val imageLabel = new JLabel("label")
  stack(
    shelf(targetPanel,imagePanel),
    imageLabel
  ).displayIn("visual logger")


  // render logger with landmarks
  val renderLogger = new ChainStateLogger[RenderParameter] {
    def overlayImages(bgImg: PixelImage[RGBA], fgImg: PixelImage[RGBA]): PixelImage[RGBA] = {
      bgImg.zip(fgImg).map { case (bg: RGBA, fg: RGBA) =>
        (bg.toRGB*0.5+RGB(0.5)).blend(fg).toRGBA
      }
    }

    override def logState(sample: RenderParameter): Unit = {
      val currentImage = renderer.renderImage(sample)
      val blendedImage = overlayImages(target,currentImage)
      val imageWithTargetLMs = LandmarksDrawer.drawLandmarks(blendedImage,landmarks,RGBA(0,0,1,0.33),8)
      val generatedLandmarks = landmarkTags.map(tag => renderer.renderLandmark(tag, sample).get)
      val imageWithBothLMs = LandmarksDrawer.drawLandmarks(
        imageWithTargetLMs,
        generatedLandmarks,
        RGBA(1.0, 0.0, 0.0),
        3
      )
      imagePanel.updateImage(imageWithBothLMs)
    }
  }

  // iteration number logger
  val iterationLogger = new ChainStateLogger[RenderParameter] {
    var index = 0
    override def logState(sample: RenderParameter): Unit = {
      index += 1
      imageLabel.setText(s"$index")
    }
  }


  // best sample loggers
  val bestLMLogger = BestSampleLogger(landmarkFittingEvaluator)
  val bestLogger = BestSampleLogger(fullImageFittingEvaluator)


  // combined loggers
  val landmarkFittingLogger = ChainStateLoggerContainer(Seq(
    iterationLogger,
    renderLogger.subSampled(100),
    bestLMLogger
  ))

  val fullImageFittingLogger = ChainStateLoggerContainer(Seq(
      iterationLogger,
      renderLogger.subSampled(100),
      bestLogger
  ))




  //=== LANDMARK FITTING - SHAPE AND POSE =======


  val lmAlgorithm = MetropolisHastings(landmarkFittingProposal,landmarkFittingEvaluator)
  val lmChainIter = lmAlgorithm.iterator(initial)
  val lmSamples = lmChainIter.take(5000).loggedWith(landmarkFittingLogger).toIndexedSeq




  //=== OPTIMIZE LIGHT FOR BEST SAMPLE OF LM-CHAIN =======

  val bestLMSample = bestLMLogger.currentBestSample().get
  val optLight = illuOptiProposal.propose(bestLMSample)



  //=== FULL FITTING - SHAPE, COLOR, POSE, LIGHT  =======

  // filter proposals first to respect the landmarks - corresponds to multiplying with the likelihood of the evaluator
  val lmConsistentProposal = MetropolisFilterProposal[RenderParameter](fullImageFittingProposal,landmarkFittingEvaluator)

  // the CorrectedMetropolisFilterProposal leads to a posterior without multiplying the likelihood of the evaluator,
  // hence ignoring this information in the final posterior
//  val lmConsistentProposal = CorrectedMetropolisFilterProposal[RenderParameter](fullImageFittingProposal,landmarkFittingEvaluator)

  // use filtered proposals in the chain and initialize with optimized light
  val algorithm = MetropolisHastings[RenderParameter](lmConsistentProposal,imageEval)
  val chainIterator = algorithm.iterator(optLight)
  val samples = chainIterator.take(500000).loggedWith(fullImageFittingLogger).toIndexedSeq


  // use sample with highest posterior value and save it
  val bestSample = bestLogger.currentBestSample().get
  renderLogger.logState(bestSample)
  RenderParameterIO.write(bestSample,new File(s"results/${id}-${modelId}.rps"))

}
