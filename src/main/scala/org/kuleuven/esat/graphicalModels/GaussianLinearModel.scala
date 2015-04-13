package org.kuleuven.esat.graphicalModels

import breeze.linalg.DenseVector
import com.github.tototoshi.csv.CSVReader
import com.tinkerpop.blueprints.{GraphFactory, Graph, Direction, Edge}
import com.tinkerpop.frames.{FramedGraph, FramedGraphFactory}
import com.tinkerpop.gremlin.scala.{ScalaEdge, ScalaVertex}
import com.typesafe.config.ConfigFactory
import org.apache.log4j.{Priority, Logger}
import org.kuleuven.esat.evaluation.Metrics
import org.kuleuven.esat.optimization._
import scala.collection.mutable
import scala.pickling._
import binary._
import collection.JavaConversions._
import org.kuleuven.esat.graphUtils._

/**
 * Linear Model with conditional probability
 * of the target variable given the features
 * is a Gaussian with mean = wT.x.
 *
 * Gaussian priors on the parameters are imposed
 * as a means for L2 regularization.
 */

private[esat] class GaussianLinearModel(
    override protected val g: FramedGraph[Graph],
    override protected val nPoints: Int,
    override protected val featuredims: Int,
    override protected val vertexMaps: (mutable.HashMap[String, AnyRef],
        mutable.HashMap[Int, AnyRef],
        mutable.HashMap[Int, AnyRef]),
    override protected val edgeMaps: (mutable.HashMap[Int, AnyRef],
      mutable.HashMap[Int, AnyRef]),
    override implicit protected val task: String)
  extends KernelBayesianModel {

  override protected val logger = Logger.getLogger(this.getClass)

  override implicit protected var params =
    DenseVector.ones[Double](featuredims)

  override protected val optimizer = GaussianLinearModel.getOptimizer(task)

  def score(point: DenseVector[Double]): Double =
    GaussianLinearModel.score(this.params)(this.featureMap(List(point))(0))

  override def predict(point: DenseVector[Double]): Double = task match {
    case "classification" => math.signum(this.score(point))
    case "regression" => this.score(point)
  }

  override def getxyPair(ed: Edge): (DenseVector[Double], Double) = {
    val edge = ScalaEdge.wrap(ed)
    val yV = ScalaVertex.wrap(edge.getVertex(Direction.IN))
    val y = yV.getProperty("value").asInstanceOf[Double]

    val xV = yV.getEdges(Direction.IN, "causes").iterator().next().getVertex(Direction.OUT)
    val x = xV.getProperty("featureMap").asInstanceOf[DenseVector[Double]]
    (x, y)
  }

  override def evaluate(reader: CSVReader, head: Boolean): Metrics[Double] =
    GaussianLinearModel.evaluate(this.featureMap)(this.params)(reader, head)

  override def filter(fn : (Int) => Boolean): List[DenseVector[Double]] =
    super.filter(fn).map((p) => p(0 to featuredims - 2))

}

object GaussianLinearModel {
  val manager: FramedGraphFactory = new FramedGraphFactory
  val conf = ConfigFactory.load("conf/bayesLearn.conf")
  val logger = Logger.getLogger(this.getClass)

  /**
   * Factory method to create the appropriate
   * optimization object required for the Gaussian
   * model
   * */
  def getOptimizer(task: String): GradientDescent = task match {
    case "classification" => new GradientDescent(
      new LeastSquaresSVMGradient(),
      new SquaredL2Updater())

    case "regression" => new GradientDescent(
      new LeastSquaresGradient(),
      new SquaredL2Updater())
  }

  /**
   * A curried function which calculates the predicted
   * value of the target variable using the parameters
   * and the point in question.
   *
   * This is used for the implementation of the method
   * score in [[GaussianLinearModel]]
   * */

  def score(params: DenseVector[Double])
           (point: DenseVector[Double]): Double =
    params(0 to params.length-2) dot point +
    params(params.length-1)

  /**
   * The actual implementation of the evaluate
   * feature.
   * */
  def evaluate(featureMap: (List[DenseVector[Double]]) => List[DenseVector[Double]])
              (params: DenseVector[Double])
              (reader: CSVReader, head: Boolean)
              (implicit task: String): Metrics[Double] = {
    val lines = reader.iterator
    var index = 1
    var dim = 0
    if(head) {
      dim = lines.next().length
    }

    logger.log(Priority.INFO, "Calculating test set predictions")

    val scoresAndLabels = lines.map{line =>
      //Parse line and extract features

      if (dim == 0) {
        dim = line.length
      }

      val yv = line.apply(line.length - 1).toDouble
      val xv: DenseVector[Double] =
        DenseVector(line.slice(0, line.length - 1).toList.map{x => x.toDouble}.toArray)

      (score(params)(featureMap(List(xv))(0)), yv)

    }.toList

    Metrics(task)(scoresAndLabels)
  }

    def apply(reader: CSVReader, head: Boolean, task: String): GaussianLinearModel = {
    val graphconfig = Map("blueprints.graph" -> "com.tinkerpop.blueprints.impls.tg.TinkerGraph")
    val wMap: mutable.HashMap[String, AnyRef] = mutable.HashMap()
    val xMap: mutable.HashMap[Int, AnyRef] = mutable.HashMap()
    val yMap: mutable.HashMap[Int, AnyRef] = mutable.HashMap()
    val ceMap: mutable.HashMap[Int, AnyRef] = mutable.HashMap()
    val peMap: mutable.HashMap[Int, AnyRef] = mutable.HashMap()
    val g = GraphFactory.open(mapAsJavaMap(graphconfig))
    val fg = manager.create(g)
    val lines = reader.iterator
    var index = 1
    var dim = 0
    if(head) {
      dim = lines.next().length
    }

    logger.log(Priority.INFO, "Creating graph for data set.")
    val pnode:Parameter[Array[Byte]] = fg.addVertex(null, classOf[Parameter[Array[Byte]]])
    pnode.setSlope(Array.fill[Double](dim)(1.0).pickle.value)
    wMap.put("w", pnode.asVertex().getId)

    while (lines.hasNext) {
      //Parse line and extract features
      val line = lines.next()
      if(dim == 0) {
        dim = line.length
      }

      val yv = line.apply(line.length - 1).toDouble
      val features = line.map((s) => s.toDouble).toArray
      features.update(line.length - 1, 1.0)
      val xv: DenseVector[Double] =
        new DenseVector[Double](features)

      /*
      * Create nodes xi and yi
      * append to them their values
      * properties, etc
      * */
      val xnode: Point[Array[Byte]] = fg.addVertex(("x", index), classOf[Point[Array[Byte]]])
      xnode.setValue(xv.toArray.pickle.value)
      xnode.setFeatureMap(xv.toArray.pickle.value)
      xMap.put(index, xnode.asVertex().getId)

      val ynode: Label[Array[Byte]] = fg.addVertex(("y", index), classOf[Label[Array[Byte]]])
      ynode.setValue(yv)
      yMap.put(index, ynode.asVertex().getId)

      //Add edge between xi and yi
      val ceEdge: CausalEdge[Array[Byte]] = fg.addEdge((1, index), xnode.asVertex(),
        ynode.asVertex(), "causes",
        classOf[CausalEdge[Array[Byte]]])
      ceEdge.setRelation("causal")
      ceMap.put(index, ceEdge.asEdge().getId)

      //Add edge between w and y_i
      val peEdge: ParamEdge[Array[Byte]] = fg.addEdge((2, index), pnode.asVertex(),
        ynode.asVertex(), "controls", classOf[ParamEdge[Array[Byte]]])
      peMap.put(index, peEdge.asEdge().getId)

      index += 1
    }

    val vMaps = (wMap, xMap, yMap)
    val eMaps = (ceMap, peMap)
    logger.log(Priority.INFO, "Graph constructed, now building model object.")
    new GaussianLinearModel(fg, index-1, dim, vMaps, eMaps, task)
  }
}
