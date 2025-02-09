package zone.cogni.asquare.cube.index;

import javax.annotation.Nonnull;
import java.util.List;

public interface FolderBasedIndexingService {

  /**
   * @return <code>true</code> if indexing is active
   */
  boolean isIndexRunning();

  /**
   * Returns list of names for indexes
   *
   * @return list of indexes managed by service for current <code>elasticStore</code>
   */
  @Nonnull
  List<String> getIndexNames();

  /**
   * Fill all indexes.
   * Can do a clear index in <code>clear</code> parameter is set to <code>true</code>.
   *
   * @param clear clears index before filling it again
   */
  void indexAll(boolean clear);

  /**
   * Fills listed indexes.
   * Can do a clear index in <code>clear</code> parameter is set to <code>true</code>.
   *
   * @param indexes list of indexes to be filled
   * @param clear   clears index before filling it again
   */
  void indexByName(@Nonnull List<String> indexes, boolean clear);

  /**
   * Fills a single index.
   * Can do a clear index in <code>clear</code> parameter is set to <code>true</code>.
   *
   * @param index to be filled
   * @param clear clears index before filling it again
   */
  void indexByName(@Nonnull String index, boolean clear);

  @Nonnull
  List<String> getCollectionNames(@Nonnull String index);

  void indexByCollection(@Nonnull String index,
                         @Nonnull List<String> collections);

  void indexByCollection(@Nonnull String index,
                         @Nonnull String collection);

  /**
   * Currently not optimized for big lists of uris.
   * All uris are processed synchronously and one by one.
   *
   * @param index      being loaded
   * @param collection of object uris being loaded in index
   * @param query      to run, returns uris which much be indexed
   */

  void indexUrisFromQuery(@Nonnull String index,
                          @Nonnull String collection,
                          @Nonnull String query);

  /**
   * Currently not optimized for big lists of uris.
   * All uris are processed synchronously and one by one.
   *
   * @param index      being loaded
   * @param collection of object uris being loaded in index
   * @param uris       being indexed
   */
  void indexUris(@Nonnull String index,
                 @Nonnull String collection,
                 @Nonnull List<String> uris);

}
