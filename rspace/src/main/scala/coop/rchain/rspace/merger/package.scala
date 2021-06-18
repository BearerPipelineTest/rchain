package coop.rchain.rspace

import scala.annotation.tailrec

package object merger {

  /** arrange list[v] into map v -> Iterator[v] for items that match predicate */
  def computeRelationMap[A](items: Set[A], relation: (A, A) => Boolean): Map[A, Set[A]] = {
    val init = items.map(_ -> Set.empty[A]).toMap
    items.toList
      .combinations(2)
      .filter {
        case List(l, r) => relation(l, r)
      }
      .foldLeft(init) {
        case (acc, List(l, r)) => acc.updated(l, acc(l) + r).updated(r, acc(r) + l)
      }
  }

  /** given relation map, return iterators of related items */
  def gatherRelatedSets[A](relationMap: Map[A, Set[A]]): Set[Set[A]] = {
    @tailrec
    def addRelations(toAdd: Set[A], acc: Set[A]): Set[A] = {
      // stop if all new dependencies are already in set
      val next = (acc ++ toAdd)
      val stop = next == acc
      if (stop)
        acc
      else {
        val next = toAdd.flatMap(v => relationMap.getOrElse(v, Set.empty))
        addRelations(next, next)
      }
    }
    relationMap.keySet.map(k => addRelations(relationMap(k), Set(k)))
  }

  def computeRejectionOptions[A](conflictMap: Map[A, Set[A]]): Set[Set[A]] = {
    @tailrec
    def process(newSet: Set[A], acc: Set[A], reject: Boolean): Set[A] = {
      // stop if all new dependencies are already in set
      val next = if (reject) (acc ++ newSet) else acc
      val stop = if (reject) next == acc else false
      if (stop)
        acc
      else {
        val next = newSet.flatMap(v => conflictMap.getOrElse(v, Set.empty))
        process(next, next, !reject)
      }
    }
    // each rejection option is defined by decision not to reject a key in rejection map
    conflictMap
    // only keys that have conflicts associated should be examined
      .filter { case (_, conflicts) => conflicts.nonEmpty }
      .keySet
      .map(k => process(conflictMap(k), Set.empty, reject = true))
  }

  def computeRelatedSets[A](items: Set[A], relation: (A, A) => Boolean): Set[Set[A]] =
    gatherRelatedSets(computeRelationMap(items, relation))
}
