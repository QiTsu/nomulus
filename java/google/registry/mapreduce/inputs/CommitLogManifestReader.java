// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.mapreduce.inputs;

import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.appengine.api.datastore.Cursor;
import com.google.appengine.api.datastore.QueryResultIterator;
import com.google.appengine.tools.mapreduce.InputReader;
import com.google.common.base.Optional;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogManifest;
import java.util.NoSuchElementException;
import org.joda.time.DateTime;

/** {@link InputReader} that maps over {@link CommitLogManifest}. */
class CommitLogManifestReader extends InputReader<CommitLogManifest> {

  private static final long serialVersionUID = 5117046535590539778L;

  /**
   * Memory estimation for this reader.
   *
   * Elements are relatively small (parent key, Id, and a set of deleted keys), so this should be
   * more than enough.
   */
  private static final long MEMORY_ESTIMATE = 100 * 1024;

  private final Key<CommitLogBucket> bucketKey;

  /**
   * Cutoff date for result.
   *
   * If present, all resulting CommitLogManifest will be dated prior to this date.
   */
  private final Optional<DateTime> olderThan;

  private Cursor cursor;
  private int total;
  private int loaded;

  private transient QueryResultIterator<CommitLogManifest> queryIterator;

  CommitLogManifestReader(Key<CommitLogBucket> bucketKey, Optional<DateTime> olderThan) {
    this.bucketKey = bucketKey;
    this.olderThan = olderThan;
  }

  /** Called once at start. Cache the expected size. */
  @Override
  public void beginShard() {
    total = query().count();
  }

  /** Called every time we are deserialized. Create a new query or resume an existing one. */
  @Override
  public void beginSlice() {
    Query<CommitLogManifest> query = query();
    if (cursor != null) {
      // The underlying query is strongly consistent, and according to the documentation at
      // https://cloud.google.com/appengine/docs/java/datastore/queries#Java_Data_consistency
      // "strongly consistent queries are always transactionally consistent". However, each time
      // we restart the query at a cursor we have a new effective query, and "if the results for a
      // query change between uses of a cursor, the query notices only changes that occur in
      // results after the cursor. If a new result appears before the cursor's position for the
      // query, it will not be returned when the results after the cursor are fetched."
      // What this means in practice is that entities that are created after the initial query
      // begins may or may not be seen by this reader, depending on whether the query was
      // paused and restarted with a cursor before it would have reached the new entity.
      query = query.startAt(cursor);
    }
    queryIterator = query.iterator();
  }

  /** Called occasionally alongside {@link #next}. */
  @Override
  public Double getProgress() {
    // Cap progress at 1.0, since the query's count() can increase during the run of the mapreduce
    // if more entities are written, but we've cached the value once in "total".
    return Math.min(1.0, ((double) loaded) / total);
  }

  /** Called before we are serialized. Save a serializable cursor for this query. */
  @Override
  public void endSlice() {
    cursor = queryIterator.getCursor();
  }

  /** Query for children of this bucket. */
  Query<CommitLogManifest> query() {
    Query<CommitLogManifest> query = ofy().load().type(CommitLogManifest.class).ancestor(bucketKey);
    if (olderThan.isPresent()) {
      query = query.filterKey(
          "<",
          Key.create(bucketKey, CommitLogManifest.class, olderThan.get().getMillis()));
    }
    return query;
  }

  /** Returns the estimated memory that will be used by this reader in bytes. */
  @Override
  public long estimateMemoryRequirement() {
    return MEMORY_ESTIMATE;
  }

  /**
   * Get the next {@link CommitLogManifest} from the query.
   *
   * @throws NoSuchElementException if there are no more elements.
   */
  @Override
  public CommitLogManifest next() {
    loaded++;
    try {
      return queryIterator.next();
    } finally {
      ofy().clearSessionCache();  // Try not to leak memory.
    }
  }
}

