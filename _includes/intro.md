
The `data/` directory contains a few sample data sets, and the root directory also has example scripts which can be executed in the shell.

First we create a linear classification model on a csv data set. We will assume that the last column in each line of the file is the target value, and we build an LS-SVM model.

{% highlight scala %}
val config = Map("file" -> "data/ripley.csv", "delim" -> ",", "head" -> "false", "task" -> "classification")
val model = LSSVMModel(config)
{% endhighlight%}

We can now (optionally) add a Kernel on the model to create a generalized linear Bayesian model.
{% highlight scala %}
val rbf = new RBFKernel(1.025)
model.applyKernel(rbf)
{% endhighlight %}

{% highlight text %}
15/08/03 19:07:42 INFO GreedyEntropySelector$: Returning final prototype set
15/08/03 19:07:42 INFO SVMKernel$: Constructing key-value representation of kernel matrix.
15/08/03 19:07:42 INFO SVMKernel$: Dimension: 13 x 13
15/08/03 19:07:42 INFO SVMKernelMatrix: Eigenvalue decomposition of the kernel matrix using JBlas.
15/08/03 19:07:42 INFO SVMKernelMatrix: Eigenvalue stats: 0.09104374173019622 =< lambda =< 3.110068839504519
15/08/03 19:07:42 INFO LSSVMModel: Applying Feature map to data set
15/08/03 19:07:42 INFO LSSVMModel: DONE: Applying Feature map to data set
DynaML>
{% endhighlight %}

Now we can solve the optimization problem posed by the LS-SVM in the parameter space. Since the LS-SVM problem is equivalent to ridge regression, we have to specify a regularization constant.

{% highlight scala %}
model.setRegParam(1.5).learn
{% endhighlight %}

We can now predict the value of the target variable given a new point consisting of a Vector of features using `model.predict()`.

Evaluating models is easy in DynaML. You can create an evaluation object as follows. 

{% highlight scala %}
val configtest = Map("file" -> "data/ripleytest.csv", "delim" -> ",", "head" -> "false")
val met = model.evaluate(configtest)
met.print
{% endhighlight %}

The object `met` has a `print()` method which will dump some performance metrics in the shell. But you can also generate plots by using the `generatePlots()` method.

{% highlight text %}
15/08/03 19:08:40 INFO BinaryClassificationMetrics: Classification Model Performance
15/08/03 19:08:40 INFO BinaryClassificationMetrics: ============================
15/08/03 19:08:40 INFO BinaryClassificationMetrics: Accuracy: 0.6172839506172839
15/08/03 19:08:40 INFO BinaryClassificationMetrics: Area under ROC: 0.2019607843137254
{% endhighlight %}

{% highlight scala %}
met.generatePlots
{% endhighlight %}

![Plots](public/Screenshot.png)

Although kernel based models allow great flexibility in modeling non linear behavior in data, they are highly sensitive to the values of their hyper-parameters. For example if we use a Radial Basis Function (RBF) Kernel, it is a non trivial problem to find the best values of the kernel bandwidth and the regularization constant.

In order to find the best hyper-parameters for a general kernel based supervised learning model, we use methods in gradient free global optimization. This is relevant because the cost (objective) function for the hyper-parameters is not smooth in general. In fact in most common scenarios the objective function is defined in terms of some kind of cross validation performance.

DynaML has a robust global optimization API, currently Coupled Simulated Annealing and Grid Search algorithms are implemented, the API in the package ```org.kuleven.esat.optimization``` can be extended to implement any general gradient or gradient free optimization methods.

Lets tune an RBF kernel on the Ripley data.

{% highlight scala %}

import com.tinkerpop.blueprints.Graph
import com.tinkerpop.frames.FramedGraph
import io.github.mandar2812.dynaml.graphutils.CausalEdge
val (optModel, optConfig) = KernelizedModel.getOptimizedModel[FramedGraph[Graph],
Iterable[CausalEdge], model.type](model, "csa",
"RBF", 13, 7, 0.3, true)
{% endhighlight %}

We see a long list of logs which end in something like the snippet below, the Coupled Simulated Annealing model, gives us a set of hyper-parameters and their values. 
{% highlight text %}
optModel: io.github.mandar2812.dynaml.models.svm.LSSVMModel = io.github.mandar2812.dynaml.models.svm.LSSVMModel@3662a98a
optConfig: scala.collection.immutable.Map[String,Double] = Map(bandwidth -> 3.824956165264642, RegParam -> 12.303758608075587)
{% endhighlight%}

To inspect the performance of this kernel model on an independent test set, we can use the ```model.evaluate()``` function. But before that we must train this 'optimized' kernel model on the training set.

{% highlight scala %}
optModel.setMaxIterations(2).learn()
val met = optModel.evaluate(configtest)
met.print()
met.generatePlots()
{% endhighlight %}

And the evaluation results follow ...

{% highlight text %}
15/08/03 19:10:13 INFO BinaryClassificationMetrics: Classification Model Performance
15/08/03 19:10:13 INFO BinaryClassificationMetrics: ============================
15/08/03 19:10:13 INFO BinaryClassificationMetrics: Accuracy: 0.8765432098765432
15/08/03 19:10:13 INFO BinaryClassificationMetrics: Area under ROC: 0.9143790849673203
{% endhighlight %}