package spark.mllib.recommendation

import scala.collection.mutable.{ArrayBuffer, BitSet}
import scala.util.Random

import spark.{HashPartitioner, Partitioner, SparkContext, RDD}
import spark.storage.StorageLevel
import spark.SparkContext._

import org.jblas.{DoubleMatrix, SimpleBlas, Solve}


/**
 * Out-link information for a user or product block. This includes the original user/product IDs
 * of the elements within this block, and the list of destination blocks that each user or
 * product will need to send its feature vector to.
 */
private[recommendation] case class OutLinkBlock(
  elementIds: Array[Int], shouldSend: Array[BitSet])


/**
 * In-link information for a user (or product) block. This includes the original user/product IDs
 * of the elements within this block, as well as an array of indices and ratings that specify
 * which user in the block will be rated by which products from each product block (or vice-versa).
 * Specifically, if this InLinkBlock is for users, ratingsForBlock(b)(i) will contain two arrays,
 * indices and ratings, for the i'th product that will be sent to us by product block b (call this
 * P). These arrays represent the users that product P had ratings for (by their index in this
 * block), as well as the corresponding rating for each one. We can thus use this information when
 * we get product block b's message to update the corresponding users.
 */
private[recommendation] case class InLinkBlock(
  elementIds: Array[Int], ratingsForBlock: Array[Array[(Array[Int], Array[Double])]])


/**
 * Alternating Least Squares matrix factorization.
 *
 * This is a blocked implementation of the ALS factorization algorithm that groups the two sets
 * of factors (referred to as "users" and "products") into blocks and reduces communication by only
 * sending one copy of each user vector to each product block on each iteration, and only for the
 * product blocks that need that user's feature vector. This is achieved by precomputing some
 * information about the ratings matrix to determine the "out-links" of each user (which blocks of
 * products it will contribute to) and "in-link" information for each product (which of the feature
 * vectors it receives from each user block it will depend on). This allows us to send only an
 * array of feature vectors between each user block and product block, and have the product block
 * find the users' ratings and update the products based on these messages.
 */
class ALS private (var numBlocks: Int, var rank: Int, var iterations: Int, var lambda: Double)
  extends Serializable
{
  def this() = this(-1, 10, 10, 0.01)

  /**
   * Set the number of blocks to parallelize the computation into; pass -1 for an auto-configured
   * number of blocks. Default: -1.
   */
  def setBlocks(numBlocks: Int): ALS = {
    this.numBlocks = numBlocks
    this
  }

  /** Set the rank of the feature matrices computed (number of features). Default: 10. */
  def setRank(rank: Int): ALS = {
    this.rank = rank
    this
  }

  /** Set the number of iterations to run. Default: 10. */
  def setIterations(iterations: Int): ALS = {
    this.iterations = iterations
    this
  }

  /** Set the regularization parameter, lambda. Default: 0.01. */
  def setLambda(lambda: Double): ALS = {
    this.lambda = lambda
    this
  }

  /**
   * Run ALS with the configured parmeters on an input RDD of (user, product, rating) triples.
   * Returns a MatrixFactorizationModel with feature vectors for each user and product.
   */
  def train(ratings: RDD[(Int, Int, Double)]): MatrixFactorizationModel = {
    val numBlocks = if (this.numBlocks == -1) {
      math.max(ratings.context.defaultParallelism, ratings.partitions.size)
    } else {
      this.numBlocks
    }

    val partitioner = new HashPartitioner(numBlocks)

    val ratingsByUserBlock = ratings.map{ case (u, p, r) => (u % numBlocks, (u, p, r)) }
    val ratingsByProductBlock = ratings.map{ case (u, p, r) => (p % numBlocks, (p, u, r)) }

    val (userInLinks, userOutLinks) = makeLinkRDDs(numBlocks, ratingsByUserBlock)
    val (productInLinks, productOutLinks) = makeLinkRDDs(numBlocks, ratingsByProductBlock)

    // Initialize user and product factors randomly
    val seed = new Random().nextInt()
    var users = userOutLinks.mapValues(_.elementIds.map(u => randomFactor(rank, seed ^ u)))
    var products = productOutLinks.mapValues(_.elementIds.map(p => randomFactor(rank, seed ^ ~p)))

    for (iter <- 0 until iterations) {
      // perform ALS update
      products = updateFeatures(users, userOutLinks, productInLinks, partitioner, rank, lambda)
      users = updateFeatures(products, productOutLinks, userInLinks, partitioner, rank, lambda)
    }

    // Flatten and cache the two final RDDs to un-block them
    val usersOut = users.join(userOutLinks).flatMap { case (b, (factors, outLinkBlock)) =>
      for (i <- 0 until factors.length) yield (outLinkBlock.elementIds(i), factors(i))
    }
    val productsOut = products.join(productOutLinks).flatMap { case (b, (factors, outLinkBlock)) =>
      for (i <- 0 until factors.length) yield (outLinkBlock.elementIds(i), factors(i))
    }

    usersOut.persist()
    productsOut.persist()

    new MatrixFactorizationModel(rank, usersOut, productsOut)
  }

  /**
   * Make the out-links table for a block of the users (or products) dataset given the list of
   * (user, product, rating) values for the users in that block (or the opposite for products).
   */
  private def makeOutLinkBlock(numBlocks: Int, ratings: Array[(Int, Int, Double)]): OutLinkBlock = {
    val userIds = ratings.map(_._1).distinct.sorted
    val numUsers = userIds.length
    val userIdToPos = userIds.zipWithIndex.toMap
    val shouldSend = Array.fill(numUsers)(new BitSet(numBlocks))
    for ((u, p, r) <- ratings) {
      shouldSend(userIdToPos(u))(p % numBlocks) = true
    }
    OutLinkBlock(userIds, shouldSend)
  }

  /**
   * Make the in-links table for a block of the users (or products) dataset given a list of
   * (user, product, rating) values for the users in that block (or the opposite for products).
   */
  private def makeInLinkBlock(numBlocks: Int, ratings: Array[(Int, Int, Double)]): InLinkBlock = {
    val userIds = ratings.map(_._1).distinct.sorted
    val numUsers = userIds.length
    val userIdToPos = userIds.zipWithIndex.toMap
    val ratingsForBlock = new Array[Array[(Array[Int], Array[Double])]](numBlocks)
    for (productBlock <- 0 until numBlocks) {
      val ratingsInBlock = ratings.filter(t => t._2 % numBlocks == productBlock)
      val ratingsByProduct = ratingsInBlock.groupBy(_._2)  // (p, Seq[(u, p, r)])
                               .toArray
                               .sortBy(_._1)
                               .map{case (p, rs) => (rs.map(t => userIdToPos(t._1)), rs.map(_._3))}
      ratingsForBlock(productBlock) = ratingsByProduct
    }
    InLinkBlock(userIds, ratingsForBlock)
  }

  /**
   * Make RDDs of InLinkBlocks and OutLinkBlocks given an RDD of (blockId, (u, p, r)) values for
   * the users (or (blockId, (p, u, r)) for the products). We create these simultaneously to avoid
   * having to shuffle the (blockId, (u, p, r)) RDD twice, or to cache it.
   */
  private def makeLinkRDDs(numBlocks: Int, ratings: RDD[(Int, (Int, Int, Double))])
    : (RDD[(Int, InLinkBlock)], RDD[(Int, OutLinkBlock)]) =
  {
    val grouped = ratings.partitionBy(new HashPartitioner(numBlocks))
    val links = grouped.mapPartitionsWithIndex((blockId, elements) => {
      val ratings = elements.map(_._2).toArray
      val inLinkBlock = makeInLinkBlock(numBlocks, ratings)
      val outLinkBlock = makeOutLinkBlock(numBlocks, ratings)
      Iterator.single((blockId, (inLinkBlock, outLinkBlock)))
    }, true)
    links.persist(StorageLevel.MEMORY_AND_DISK)
    (links.mapValues(_._1), links.mapValues(_._2))
  }

  /**
   * Make a random factor vector with the given seed.
   * TODO: Initialize things using mapPartitionsWithIndex to make it faster?
   */
  private def randomFactor(rank: Int, seed: Int): Array[Double] = {
    val rand = new Random(seed)
    Array.fill(rank)(rand.nextDouble)
  }

  /**
   * Compute the user feature vectors given the current products (or vice-versa). This first joins
   * the products with their out-links to generate a set of messages to each destination block
   * (specifically, the features for the products that user block cares about), then groups these
   * by destination and joins them with the in-link info to figure out how to update each user.
   * It returns an RDD of new feature vectors for each user block.
   */
  private def updateFeatures(
      products: RDD[(Int, Array[Array[Double]])],
      productOutLinks: RDD[(Int, OutLinkBlock)],
      userInLinks: RDD[(Int, InLinkBlock)],
      partitioner: Partitioner,
      rank: Int,
      lambda: Double)
    : RDD[(Int, Array[Array[Double]])] =
  {
    val numBlocks = products.partitions.size
    productOutLinks.join(products).flatMap { case (bid, (outLinkBlock, factors)) =>
        val toSend = Array.fill(numBlocks)(new ArrayBuffer[Array[Double]])
        for (p <- 0 until outLinkBlock.elementIds.length; userBlock <- 0 until numBlocks) {
          if (outLinkBlock.shouldSend(p)(userBlock)) {
            toSend(userBlock) += factors(p)
          }
        }
        toSend.zipWithIndex.map{ case (buf, idx) => (idx, (bid, buf.toArray)) }
    }.groupByKey(partitioner)
     .join(userInLinks)
     .mapValues{ case (messages, inLinkBlock) => updateBlock(messages, inLinkBlock, rank, lambda) }
  }

  /**
   * Compute the new feature vectors for a block of the users matrix given the list of factors
   * it received from each product and its InLinkBlock.
   */
  def updateBlock(messages: Seq[(Int, Array[Array[Double]])], inLinkBlock: InLinkBlock,
      rank: Int, lambda: Double)
    : Array[Array[Double]] =
  {
    // Sort the incoming block factor messages by block ID and make them an array
    val blockFactors = messages.sortBy(_._1).map(_._2).toArray // Array[Array[Double]]
    val numBlocks = blockFactors.length
    val numUsers = inLinkBlock.elementIds.length

    // We'll sum up the XtXes using vectors that represent only the lower-triangular part, since
    // the matrices are symmetric
    val triangleSize = rank * (rank + 1) / 2
    val userXtX = Array.fill(numUsers)(DoubleMatrix.zeros(triangleSize))
    val userXy = Array.fill(numUsers)(DoubleMatrix.zeros(rank))

    // Some temp variables to avoid memory allocation
    val tempXtX = DoubleMatrix.zeros(triangleSize)
    val fullXtX = DoubleMatrix.zeros(rank, rank)

    // Compute the XtX and Xy values for each user by adding products it rated in each product block
    for (productBlock <- 0 until numBlocks) {
      for (p <- 0 until blockFactors(productBlock).length) {
        val x = new DoubleMatrix(blockFactors(productBlock)(p))
        fillXtX(x, tempXtX)
        val (us, rs) = inLinkBlock.ratingsForBlock(productBlock)(p)
        for (i <- 0 until us.length) {
          userXtX(us(i)).addi(tempXtX)
          SimpleBlas.axpy(rs(i), x, userXy(us(i)))
        }
      }
    }

    // Solve the least-squares problem for each user and return the new feature vectors
    userXtX.zipWithIndex.map{ case (triangularXtX, index) =>
      // Compute the full XtX matrix from the lower-triangular part we got above
      fillFullMatrix(triangularXtX, fullXtX)
      // Add regularization
      (0 until rank).foreach(i => fullXtX.data(i*rank + i) += lambda)
      // Solve the resulting matrix, which is symmetric and positive-definite
      Solve.solvePositive(fullXtX, userXy(index)).data
    }
  }

  /**
   * Set xtxDest to the lower-triangular part of x transpose * x. For efficiency in summing
   * these matrices, we store xtxDest as only rank * (rank+1) / 2 values, namely the values
   * at (0,0), (1,0), (1,1), (2,0), (2,1), (2,2), etc in that order.
   */
  private def fillXtX(x: DoubleMatrix, xtxDest: DoubleMatrix) {
    var i = 0
    var pos = 0
    while (i < x.length) {
      var j = 0
      while (j <= i) {
        xtxDest.data(pos) = x.data(i) * x.data(j)
        pos += 1
        j += 1
      }
      i += 1
    }
  }

  /**
   * Given a triangular matrix in the order of fillXtX above, compute the full symmetric square
   * matrix that it represents, storing it into destMatrix.
   */
  private def fillFullMatrix(triangularMatrix: DoubleMatrix, destMatrix: DoubleMatrix) {
    val rank = destMatrix.rows
    var i = 0
    var pos = 0
    while (i < rank) {
      var j = 0
      while (j <= i) {
        destMatrix.data(i*rank + j) = triangularMatrix.data(pos)
        destMatrix.data(j*rank + i) = triangularMatrix.data(pos)
        pos += 1
        j += 1
      }
      i += 1
    }
  }
}


/**
 * Top-level methods for calling Alternating Least Squares (ALS) matrix factorizaton.
 */
object ALS {
  /**
   * Train a matrix factorization model given an RDD of ratings given by users to some products,
   * in the form of (userID, productID, rating) pairs. We approximate the ratings matrix as the
   * product of two lower-rank matrices of a given rank (number of features). To solve for these
   * features, we run a given number of iterations of ALS. This is done using a level of
   * parallelism given by `blocks`.
   *
   * @param ratings    RDD of (userID, productID, rating) pairs
   * @param rank       number of features to use
   * @param iterations number of iterations of ALS (recommended: 10-20)
   * @param lambda     regularization factor (recommended: 0.01)
   * @param blocks     level of parallelism to split computation into
   */
  def train(
      ratings: RDD[(Int, Int, Double)],
      rank: Int,
      iterations: Int,
      lambda: Double,
      blocks: Int)
    : MatrixFactorizationModel =
  {
    new ALS(blocks, rank, iterations, lambda).train(ratings)
  }

  /**
   * Train a matrix factorization model given an RDD of ratings given by users to some products,
   * in the form of (userID, productID, rating) pairs. We approximate the ratings matrix as the
   * product of two lower-rank matrices of a given rank (number of features). To solve for these
   * features, we run a given number of iterations of ALS. The level of parallelism is determined
   * automatically based on the number of partitions in `ratings`.
   *
   * @param ratings    RDD of (userID, productID, rating) pairs
   * @param rank       number of features to use
   * @param iterations number of iterations of ALS (recommended: 10-20)
   * @param lambda     regularization factor (recommended: 0.01)
   */
  def train(ratings: RDD[(Int, Int, Double)], rank: Int, iterations: Int, lambda: Double)
    : MatrixFactorizationModel =
  {
    train(ratings, rank, iterations, lambda, -1)
  }

  /**
   * Train a matrix factorization model given an RDD of ratings given by users to some products,
   * in the form of (userID, productID, rating) pairs. We approximate the ratings matrix as the
   * product of two lower-rank matrices of a given rank (number of features). To solve for these
   * features, we run a given number of iterations of ALS. The level of parallelism is determined
   * automatically based on the number of partitions in `ratings`.
   *
   * @param ratings    RDD of (userID, productID, rating) pairs
   * @param rank       number of features to use
   * @param iterations number of iterations of ALS (recommended: 10-20)
   */
  def train(ratings: RDD[(Int, Int, Double)], rank: Int, iterations: Int)
    : MatrixFactorizationModel =
  {
    train(ratings, rank, iterations, 0.01, -1)
  }

  def main(args: Array[String]) {
    if (args.length != 5) {
      println("Usage: ALS <master> <ratings_file> <rank> <iterations> <output_dir>")
      System.exit(1)
    }
    val (master, ratingsFile, rank, iters, outputDir) =
      (args(0), args(1), args(2).toInt, args(3).toInt, args(4))
    val sc = new SparkContext(master, "ALS")
    val ratings = sc.textFile(ratingsFile).map { line =>
      val fields = line.split(',')
      (fields(0).toInt, fields(1).toInt, fields(2).toDouble)
    }
    val model = ALS.train(ratings, rank, iters)
    model.userFeatures.map{ case (id, vec) => id + "," + vec.mkString(" ") }
                      .saveAsTextFile(outputDir + "/userFeatures")
    model.productFeatures.map{ case (id, vec) => id + "," + vec.mkString(" ") }
                         .saveAsTextFile(outputDir + "/productFeatures")
    println("Final user/product features written to " + outputDir)
    System.exit(0)
  }
}
