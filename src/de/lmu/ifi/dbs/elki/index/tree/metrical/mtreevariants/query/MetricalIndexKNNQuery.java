package de.lmu.ifi.dbs.elki.index.tree.metrical.mtreevariants.query;

/*
 This file is part of ELKI:
 Environment for Developing KDD-Applications Supported by Index-Structures

 Copyright (C) 2012
 Ludwig-Maximilians-Universität München
 Lehr- und Forschungseinheit für Datenbanksysteme
 ELKI Development Team

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.List;

import de.lmu.ifi.dbs.elki.database.ids.ArrayDBIDs;
import de.lmu.ifi.dbs.elki.database.ids.DBID;
import de.lmu.ifi.dbs.elki.database.ids.DBIDRef;
import de.lmu.ifi.dbs.elki.database.query.distance.DistanceQuery;
import de.lmu.ifi.dbs.elki.database.query.knn.AbstractDistanceKNNQuery;
import de.lmu.ifi.dbs.elki.distance.DistanceUtil;
import de.lmu.ifi.dbs.elki.distance.distanceresultlist.KNNHeap;
import de.lmu.ifi.dbs.elki.distance.distanceresultlist.KNNResult;
import de.lmu.ifi.dbs.elki.distance.distanceresultlist.KNNUtil;
import de.lmu.ifi.dbs.elki.distance.distancevalue.Distance;
import de.lmu.ifi.dbs.elki.index.tree.DirectoryEntry;
import de.lmu.ifi.dbs.elki.index.tree.metrical.mtreevariants.AbstractMTree;
import de.lmu.ifi.dbs.elki.index.tree.metrical.mtreevariants.AbstractMTreeNode;
import de.lmu.ifi.dbs.elki.index.tree.metrical.mtreevariants.MTreeEntry;
import de.lmu.ifi.dbs.elki.index.tree.query.GenericMTreeDistanceSearchCandidate;
import de.lmu.ifi.dbs.elki.utilities.datastructures.heap.Heap;
import de.lmu.ifi.dbs.elki.utilities.datastructures.heap.UpdatableHeap;
import de.lmu.ifi.dbs.elki.utilities.exceptions.ExceptionMessages;

/**
 * Instance of a KNN query for a particular spatial index.
 * 
 * @author Erich Schubert
 * 
 * @apiviz.uses AbstractMTree
 * 
 * @param <O> Object type
 * @param <D> Distance type
 */
public class MetricalIndexKNNQuery<O, D extends Distance<D>> extends AbstractDistanceKNNQuery<O, D> {
  /**
   * The index to use
   */
  protected final AbstractMTree<O, D, ?, ?> index;

  /**
   * Constructor.
   * 
   * @param index Index to use
   * @param distanceQuery Distance query used
   */
  public MetricalIndexKNNQuery(AbstractMTree<O, D, ?, ?> index, DistanceQuery<O, D> distanceQuery) {
    super(distanceQuery);
    this.index = index;
  }

  /**
   * Performs a k-nearest neighbor query for the given FeatureVector with the
   * given parameter k and the according distance function. The query result is
   * in ascending order to the distance to the query object.
   * 
   * @param q the id of the query object
   * @param knnList the query result list
   */
  protected final void doKNNQuery(O q, KNNHeap<D> knnList) {
    final Heap<GenericMTreeDistanceSearchCandidate<D>> pq = new UpdatableHeap<GenericMTreeDistanceSearchCandidate<D>>();

    // push root
    pq.add(new GenericMTreeDistanceSearchCandidate<D>(distanceQuery.nullDistance(), index.getRootID(), null));
    D d_k = knnList.getKNNDistance();

    // search in tree
    while(!pq.isEmpty()) {
      GenericMTreeDistanceSearchCandidate<D> pqNode = pq.poll();

      if(d_k != null && pqNode.mindist.compareTo(d_k) > 0) {
        return;
      }

      AbstractMTreeNode<O, D, ?, ?> node = index.getNode(pqNode.nodeID);
      DBID o_p = pqNode.routingObjectID;

      // directory node
      if(!node.isLeaf()) {
        for(int i = 0; i < node.getNumEntries(); i++) {
          MTreeEntry<D> entry = node.getEntry(i);
          DBID o_r = entry.getRoutingObjectID();
          D r_or = entry.getCoveringRadius();
          D d1 = o_p != null ? distanceQuery.distance(o_p, q) : distanceQuery.nullDistance();
          D d2 = o_p != null ? distanceQuery.distance(o_r, o_p) : distanceQuery.nullDistance();

          D diff = d1.compareTo(d2) > 0 ? d1.minus(d2) : d2.minus(d1);

          if(d_k == null || diff.compareTo(d_k.plus(r_or)) <= 0) {
            D d3 = distanceQuery.distance(o_r, q);
            D d_min = DistanceUtil.max(d3.minus(r_or), distanceQuery.nullDistance());
            if(d_k == null || d_min.compareTo(d_k) <= 0) {
              pq.add(new GenericMTreeDistanceSearchCandidate<D>(d_min, ((DirectoryEntry) entry).getPageID(), o_r));
            }
          }
        }

      }

      // data node
      else {
        for(int i = 0; i < node.getNumEntries(); i++) {
          MTreeEntry<D> entry = node.getEntry(i);
          DBID o_j = entry.getRoutingObjectID();

          D d1 = o_p != null ? distanceQuery.distance(o_p, q) : distanceQuery.nullDistance();
          D d2 = o_p != null ? distanceQuery.distance(o_j, o_p) : distanceQuery.nullDistance();

          D diff = d1.compareTo(d2) > 0 ? d1.minus(d2) : d2.minus(d1);

          if(d_k == null || diff.compareTo(d_k) <= 0) {
            D d3 = distanceQuery.distance(o_j, q);
            if(d_k == null || d3.compareTo(d_k) <= 0) {
              knnList.add(d3, o_j);
              d_k = knnList.getKNNDistance();
            }
          }
        }
      }
    }
  }

  @Override
  public KNNResult<D> getKNNForObject(O q, int k) {
    if(k < 1) {
      throw new IllegalArgumentException("At least one object has to be requested!");
    }

    KNNHeap<D> knnList = KNNUtil.newHeap(distanceQuery.getDistanceFactory(), k);
    D d_k = knnList.getKNNDistance();
    
    final Heap<GenericMTreeDistanceSearchCandidate<D>> pq = new Heap<GenericMTreeDistanceSearchCandidate<D>>();

    // push root
    pq.add(new GenericMTreeDistanceSearchCandidate<D>(index.getDistanceFactory().nullDistance(), index.getRootID(), null));
    
    // search in tree
    while(!pq.isEmpty()) {
      GenericMTreeDistanceSearchCandidate<D> pqNode = pq.poll();

      if(knnList.size() >= k && pqNode.mindist.compareTo(d_k) > 0) {
        break;
      }

      AbstractMTreeNode<?, D, ?, ?> node = index.getNode(pqNode.nodeID);
      DBID o_p = pqNode.routingObjectID;

      // directory node
      if(!node.isLeaf()) {
        for(int i = 0; i < node.getNumEntries(); i++) {
          MTreeEntry<D> entry = node.getEntry(i);
          DBID o_r = entry.getRoutingObjectID();
          D r_or = entry.getCoveringRadius();
          D d1 = o_p != null ? distanceQuery.distance(o_p, q) : index.getDistanceFactory().nullDistance();
          D d2 = o_p != null ? distanceQuery.distance(o_r, o_p) : index.getDistanceFactory().nullDistance();

          D diff = d1.compareTo(d2) > 0 ? d1.minus(d2) : d2.minus(d1);

          D sum = d_k.plus(r_or);

          if(diff.compareTo(sum) <= 0) {
            D d3 = distanceQuery.distance(o_r, q);
            D d_min = DistanceUtil.max(d3.minus(r_or), index.getDistanceFactory().nullDistance());
            if(d_min.compareTo(d_k) <= 0) {
              pq.add(new GenericMTreeDistanceSearchCandidate<D>(d_min, ((DirectoryEntry)entry).getPageID(), o_r));
            }
          }
        }
      }
      // data node
      else {
        for(int i = 0; i < node.getNumEntries(); i++) {
          MTreeEntry<D> entry = node.getEntry(i);
          DBID o_j = entry.getRoutingObjectID();

          D d1 = o_p != null ? distanceQuery.distance(o_p, q) : index.getDistanceFactory().nullDistance();
          D d2 = o_p != null ? distanceQuery.distance(o_j, o_p) : index.getDistanceFactory().nullDistance();

          D diff = d1.compareTo(d2) > 0 ? d1.minus(d2) : d2.minus(d1);

          if(diff.compareTo(d_k) <= 0) {
            D d3 = distanceQuery.distance(o_j, q);
            if(d3.compareTo(d_k) <= 0) {
              knnList.add(d3, o_j);
              d_k = knnList.getKNNDistance();
            }
          }
        }
      }
    }
    return knnList.toKNNList();
  }

  @Override
  public KNNResult<D> getKNNForDBID(DBIDRef id, int k) {
    return getKNNForObject(relation.get(id), k);
  }

  @Override
  public List<KNNResult<D>> getKNNForBulkDBIDs(ArrayDBIDs ids, int k) {
    // TODO: implement
    throw new UnsupportedOperationException(ExceptionMessages.UNSUPPORTED_NOT_YET);
  }
}