import org.apache.spark.graphx.Edge 
import org.apache.spark.graphx.Graph
import org.apache.spark.rdd.RDD

import scala.collection.mutable.ListBuffer
import org.apache.spark.HashPartitioner

val NB_FRAGMENTS = sc.defaultParallelism
val part =  sc.defaultParallelism 

val directory = "/home/oliv/git/SPARQL-Spark/liteMat++/drugbankExt/"
val file = "drugbankExt.nt"

// Load the Drugbank data set
val triples0 = sc.textFile(directory+file).map(x=>x.split(" ")).map(t=>(t(0),t(1),t(2)))

// create an RDD made of the owl:sameAs statements
val sameAs = triples0.filter{case(s,p,o)=>p=="<http://www.w3.org/2002/07/owl#sameAs>"}.map{case(s,p,o)=>(s,o)}

// create an RDD containing individuals involved in sameAs triples
val sameAsInd = sameAs.flatMap{case(s,o)=>Array(s,o)}.distinct

// provide a unique Long id to all sameAs individuals
val sameAsIndId = sameAsInd.zipWithUniqueId

// create edges of the graph
val sameAsEdges = sameAs.join(sameAsIndId).map{case(s,(o,id))=>(o,id)}.join(sameAsIndId).map{case(o,(idS,idO))=>Edge(idS,idO,null)}

// create sameAs graph
val sameAsGraph = Graph(sameAsIndId.map{case(uri,id)=>(id,uri)}, sameAsEdges)

// Compute connected components of the graph
val connectedComponents = sameAsGraph.connectedComponents

// create an RDD containing the ids of connected components
val sameAsGroup = connectedComponents.vertices.map{x=> x._2}.distinct

// create an RDD made of statements where the property is not owl:sameAs
val nonSameAs = triples0.filter{case(s,p,o)=>p!="<http://www.w3.org/2002/07/owl#sameAs>"}.map{case(s,p,o)=>(s,o.replace('\"',' ').trim)}

// create an RDD containing nonSameAs individuals
val nonSameAsInd = nonSameAs.flatMap{case(s,o)=>Array(s,o)}.distinct.subtract(sameAsInd)

// number of bits required for the encoding of sameAs individuals
val nonSameAsBit = (Math.log(nonSameAsInd.count*2)/Math.log(2)).ceil

val nonSameAsDictionary = nonSameAsInd.zipWithUniqueId

// number of bits required for the encoding of sameAs individuals
val sameAsBit = (Math.log(sameAsGroup.count*2)/Math.log(2)).ceil

///////////////////////////////////////////
// Encode sameAsIndividuals

// create an RDD containing the connected component id and an array of all URI in that individual cluster
val sameAsURIConnectedComp = sameAsIndId.map(x=>(x._2,x._1)).join(connectedComponents.vertices.map(x=>x)).map{case(k,(uri,cid))=>(cid,uri)}.groupByKey

// compute max number of members in a sameAsGroup
val maxGroupSize = sameAsURIConnectedComp.map(x=> (x._2.size)).reduce(Math.max)

// compute number of bits required for the largest sameAs group
val maxGroupSizeBit = (Math.log(maxGroupSize)/math.log(2)).ceil

def zipId(map : Iterable[String] ) : List[(Long,String)] = {
  var res = ListBuffer[(Long,String)]()
  var cumul : Long = 1;
  var i = map.iterator
  while (i.hasNext) {
    res.append((cumul,i.next))
    cumul = cumul + 1
  }
  return res.toList
}

// Signature : connectedComponent, sameAsId, sameAsLabel
val sameAsDictionaryTemp = sameAsURIConnectedComp.map{case(id, l)=> (id, zipId(l))}.flatMap{case(cid, list) => list.map{case(id, u)=> (cid.toLong,id,u)}}

def dicoRDD(saBit : Long, nonsaBit: Long, rdd : RDD[(Long, Long, String)]) : RDD[(Long, String)] = {
    return rdd.map{case(cid,id,uri) => (1<<(saBit+nonsaBit) | (cid<<nonsaBit) + id, uri)}
}

// compute the size required for right most part for the sameAs groups
val rightSizeBit = Math.max(maxGroupSizeBit,nonSameAsBit.toLong)

// compute de sameAsDictionary
val sameAsDictionary = dicoRDD(sameAsBit.toLong, rightSizeBit.toLong, sameAsDictionaryTemp)

// Create metadata RDD
val metadata = sc.parallelize(Array(("saGroupBits "+sameAsBit.toLong),("saLocalBits "+rightSizeBit.toLong)))

// store dictionaries
nonSameAsDictionary.map(x=> x._2+" "+x._1).saveAsTextFile(directory+"/dct/nonSameAs.dct")
sameAsDictionary.map(x=>x._1+" "+x._2).saveAsTextFile(directory+"/dct/sameAs.dct")
metadata.saveAsTextFile(directory+"/dct/metadata")


