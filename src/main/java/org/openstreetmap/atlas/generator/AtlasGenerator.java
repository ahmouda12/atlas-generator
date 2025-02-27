package org.openstreetmap.atlas.generator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.broadcast.Broadcast;
import org.openstreetmap.atlas.exception.CoreException;
import org.openstreetmap.atlas.generator.AtlasGeneratorHelper.NamedAtlasStatistics;
import org.openstreetmap.atlas.generator.persistence.MultipleLineDelimitedGeojsonOutputFormat;
import org.openstreetmap.atlas.generator.persistence.scheme.SlippyTilePersistenceScheme;
import org.openstreetmap.atlas.generator.sharding.AtlasSharding;
import org.openstreetmap.atlas.generator.tools.filesystem.FileSystemHelper;
import org.openstreetmap.atlas.generator.tools.spark.SparkJob;
import org.openstreetmap.atlas.generator.tools.spark.persistence.PersistenceTools;
import org.openstreetmap.atlas.geography.atlas.Atlas;
import org.openstreetmap.atlas.geography.atlas.delta.AtlasDelta;
import org.openstreetmap.atlas.geography.atlas.statistics.AtlasStatistics;
import org.openstreetmap.atlas.geography.atlas.sub.AtlasCutType;
import org.openstreetmap.atlas.geography.boundary.CountryBoundaryMap;
import org.openstreetmap.atlas.geography.boundary.CountryBoundaryMapArchiver;
import org.openstreetmap.atlas.geography.boundary.CountryShardListing;
import org.openstreetmap.atlas.geography.sharding.Shard;
import org.openstreetmap.atlas.geography.sharding.Sharding;
import org.openstreetmap.atlas.tags.Taggable;
import org.openstreetmap.atlas.utilities.collections.StringList;
import org.openstreetmap.atlas.utilities.configuration.ConfiguredFilter;
import org.openstreetmap.atlas.utilities.maps.MultiMapWithSet;
import org.openstreetmap.atlas.utilities.runtime.CommandMap;
import org.openstreetmap.atlas.utilities.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Tuple2;

/**
 * Generate {@link Atlas} Shards for a specific version and a specific set of countries
 *
 * @author matthieun
 * @author mgostintsev
 * @author samgass
 */
public class AtlasGenerator extends SparkJob
{

    public static final String LINE_DELIMITED_GEOJSON_STATISTICS_FOLDER = "ldgeojson";

    private static final long serialVersionUID = 5985696743749843135L;
    private static final Logger logger = LoggerFactory.getLogger(AtlasGenerator.class);
    private static final String SAVED_MESSAGE = "\n\n********** SAVED FOR STEP: {} **********\n";
    private static final String EXCEPTION_MESSAGE = "Exception after task {} :";

    public static void main(final String[] args)
    {
        new AtlasGenerator().run(args);
    }

    /**
     * Generates a {@link List} of {@link AtlasGenerationTask}s for given countries using given
     * {@link CountryBoundaryMap} and {@link Sharding} strategy.
     *
     * @param countries
     *            Countries to generate tasks for
     * @param boundaryMap
     *            {@link CountryBoundaryMap} to read country boundaries
     * @param sharding
     *            {@link Sharding} strategy
     * @return {@link List} of {@link AtlasGenerationTask}s
     */
    protected static List<AtlasGenerationTask> generateTasks(final List<String> countries,
            final CountryBoundaryMap boundaryMap, final Sharding sharding)
    {
        final MultiMapWithSet<String, Shard> countryToShardMap = CountryShardListing
                .countryToShardList(countries, boundaryMap, sharding);
        // Generate tasks from country-shard map
        final List<AtlasGenerationTask> tasks = new ArrayList<>();
        countryToShardMap.keySet().forEach(country ->
        {
            final Set<Shard> shards = countryToShardMap.get(country);
            if (!shards.isEmpty())
            {
                shards.forEach(shard -> tasks.add(new AtlasGenerationTask(country, shard, shards)));
            }
            else
            {
                logger.warn("No shards were found for {}. Skipping task generation.", country);
            }
        });

        return tasks;
    }

    @Override
    public String getName()
    {
        return "Atlas Generator";
    }

    @Override
    public void start(final CommandMap command)
    {
        final Map<String, String> sparkContext = configurationMap();

        final List<String> countries = ((StringList) command
                .get(AtlasGeneratorParameters.COUNTRIES)).stream().collect(Collectors.toList());
        final String previousOutputForDelta = (String) command
                .get(AtlasGeneratorParameters.PREVIOUS_OUTPUT_FOR_DELTA);
        final String pbfPath = (String) command.get(AtlasGeneratorParameters.PBF_PATH);
        final SlippyTilePersistenceScheme pbfScheme = (SlippyTilePersistenceScheme) command
                .get(AtlasGeneratorParameters.PBF_SCHEME);
        final SlippyTilePersistenceScheme atlasScheme = (SlippyTilePersistenceScheme) command
                .get(AtlasGeneratorParameters.ATLAS_SCHEME);
        final Sharding sharding = atlasSharding(command);
        final Sharding pbfSharding = pbfSharding(command);
        final PbfContext pbfContext = new PbfContext(pbfPath, pbfSharding, pbfScheme);
        final String shouldIncludeFilteredOutputConfiguration = (String) command
                .get(AtlasGeneratorParameters.SHOULD_INCLUDE_FILTERED_OUTPUT_CONFIGURATION);
        final String configuredFilterPath = (String) command
                .get(AtlasGeneratorParameters.CONFIGURED_FILTER_OUTPUT);
        final String configuredFilterName = (String) command
                .get(AtlasGeneratorParameters.CONFIGURED_FILTER_NAME);
        final Predicate<Taggable> taggableOutputFilter;
        ConfiguredFilter configuredOutputFilter = null;
        if (shouldIncludeFilteredOutputConfiguration == null)
        {
            taggableOutputFilter = taggable -> false;
        }
        else
        {
            taggableOutputFilter = AtlasGeneratorParameters.getTaggableFilterFrom(FileSystemHelper
                    .resource(shouldIncludeFilteredOutputConfiguration, sparkContext));
        }
        if (configuredFilterPath != null)
        {
            if (configuredFilterName == null)
            {
                throw new CoreException(
                        "A filter name must be provided for configured filter output!");
            }
            configuredOutputFilter = AtlasGeneratorParameters.getConfiguredFilterFrom(
                    configuredFilterName,
                    FileSystemHelper.resource(configuredFilterPath, sparkContext));
        }

        final String output = output(command);
        final boolean lineDelimitedGeojsonOutput = (boolean) command
                .get(AtlasGeneratorParameters.LINE_DELIMITED_GEOJSON_OUTPUT);

        final CountryBoundaryMap boundaries = boundaries(countries, command);

        // Generate country-shard generation tasks
        final Time timer = Time.now();
        final List<AtlasGenerationTask> tasks = generateTasks(countries, boundaries, sharding);
        logger.debug("Generated {} tasks in {}.", tasks.size(), timer.elapsedSince());

        // AtlasLoadingOption isn't serializable, neither is command map. To avoid duplicating
        // boiler-plate code for creating the AtlasLoadingOption, extract the properties we need
        // from the command map and pass those around to create the AtlasLoadingOption
        final Map<String, String> atlasLoadingOptions = AtlasGeneratorParameters
                .extractAtlasLoadingProperties(command, sparkContext);

        // Leverage Spark broadcast to have a read-only variable cached on each machine, instead of
        // shipping a copy with each task. All of these are re-used across tasks and are unchanged.
        final Broadcast<CountryBoundaryMap> broadcastBoundaries = getContext()
                .broadcast(boundaries);
        final Broadcast<Map<String, String>> broadcastLoadingOptions = getContext()
                .broadcast(atlasLoadingOptions);
        final Broadcast<Sharding> broadcastSharding = getContext().broadcast(sharding);

        // Generate the raw Atlas and filter any null atlases
        final JavaPairRDD<String, Atlas> countryRawAtlasRDD = getContext()
                .parallelize(tasks, tasks.size())
                .mapToPair(AtlasGeneratorHelper.generateRawAtlas(broadcastBoundaries, sparkContext,
                        broadcastLoadingOptions, pbfContext, atlasScheme))
                .filter(tuple -> tuple._2() != null);
        countryRawAtlasRDD.cache();

        // Persist the RDD and save the intermediary state
        saveAsHadoop(countryRawAtlasRDD, AtlasGeneratorJobGroup.RAW, output);

        // Slice the raw Atlas and filter any null atlases
        final JavaPairRDD<String, Atlas> lineSlicedAtlasRDD = countryRawAtlasRDD.mapToPair(
                AtlasGeneratorHelper.sliceLines(broadcastBoundaries, broadcastLoadingOptions))
                .filter(tuple -> tuple._2() != null);
        lineSlicedAtlasRDD.cache();
        saveAsHadoop(lineSlicedAtlasRDD, AtlasGeneratorJobGroup.LINE_SLICED, output);

        // Remove the raw atlas RDD from cache since we've cached the sliced RDD
        try
        {
            countryRawAtlasRDD.unpersist();
        }
        catch (final Exception exception)
        {
            logger.warn(EXCEPTION_MESSAGE, AtlasGeneratorJobGroup.LINE_SLICED.getDescription(),
                    exception);
        }

        // Subatlas the raw shard Atlas files based on water relations
        final Predicate<Taggable> slicingFilter = AtlasGeneratorParameters
                .buildAtlasLoadingOption(broadcastBoundaries.getValue(),
                        broadcastLoadingOptions.getValue())
                .getSlicingFilter();
        final JavaPairRDD<String, Atlas> lineSlicedSubAtlasRDD = lineSlicedAtlasRDD
                .mapToPair(AtlasGeneratorHelper.subatlas(slicingFilter, AtlasCutType.SILK_CUT))
                .filter(tuple -> tuple._2() != null);
        lineSlicedSubAtlasRDD.cache();
        saveAsHadoop(lineSlicedSubAtlasRDD, AtlasGeneratorJobGroup.LINE_SLICED_SUB, output);

        // Relation slice the line sliced Atlas and filter any null atlases
        final JavaPairRDD<String, Atlas> fullySlicedRawAtlasShardsRDD = lineSlicedAtlasRDD
                .mapToPair(AtlasGeneratorHelper.sliceRelations(broadcastBoundaries,
                        broadcastLoadingOptions, broadcastSharding,
                        getAlternateSubFolderOutput(output,
                                AtlasGeneratorJobGroup.LINE_SLICED_SUB.getCacheFolder()),
                        getAlternateSubFolderOutput(output,
                                AtlasGeneratorJobGroup.LINE_SLICED.getCacheFolder()),
                        atlasScheme, sparkContext))
                .filter(tuple -> tuple._2() != null);
        fullySlicedRawAtlasShardsRDD.cache();
        saveAsHadoop(fullySlicedRawAtlasShardsRDD, AtlasGeneratorJobGroup.FULLY_SLICED, output);

        // Remove the line sliced atlas RDD from cache since we've cached the fully sliced RDD
        try
        {
            lineSlicedAtlasRDD.unpersist();
            lineSlicedSubAtlasRDD.unpersist();
        }
        catch (final Exception exception)
        {
            logger.warn(EXCEPTION_MESSAGE, AtlasGeneratorJobGroup.FULLY_SLICED.getDescription(),
                    exception);
        }
        final Predicate<Taggable> edgeFilter = AtlasGeneratorParameters.buildAtlasLoadingOption(
                broadcastBoundaries.getValue(), broadcastLoadingOptions.getValue()).getEdgeFilter();
        final JavaPairRDD<String, Atlas> edgeOnlySubAtlasRDD = fullySlicedRawAtlasShardsRDD
                .mapToPair(AtlasGeneratorHelper.subatlas(edgeFilter, AtlasCutType.SILK_CUT))
                .filter(tuple -> tuple._2() != null);
        edgeOnlySubAtlasRDD.cache();
        saveAsHadoop(edgeOnlySubAtlasRDD, AtlasGeneratorJobGroup.EDGE_SUB, output);

        // Section the sliced Atlas
        final JavaPairRDD<String, Atlas> countryAtlasShardsRDD = fullySlicedRawAtlasShardsRDD
                .mapToPair(AtlasGeneratorHelper.sectionAtlas(broadcastBoundaries, broadcastSharding,
                        sparkContext, broadcastLoadingOptions,
                        getAlternateSubFolderOutput(output,
                                AtlasGeneratorJobGroup.EDGE_SUB.getCacheFolder()),
                        getAlternateSubFolderOutput(output,
                                AtlasGeneratorJobGroup.FULLY_SLICED.getCacheFolder()),
                        atlasScheme, tasks));
        countryAtlasShardsRDD.cache();
        saveAsHadoop(countryAtlasShardsRDD, AtlasGeneratorJobGroup.WAY_SECTIONED_PBF, output);
        this.copyToOutput(command, pbfPath, getAlternateSubFolderOutput(output,
                AtlasGeneratorJobGroup.WAY_SECTIONED_PBF.getCacheFolder()));

        // Remove the edge-only subatlas as we've finished way-sectioning
        try
        {
            edgeOnlySubAtlasRDD.unpersist();
        }
        catch (final Exception exception)
        {
            logger.warn(EXCEPTION_MESSAGE,
                    AtlasGeneratorJobGroup.WAY_SECTIONED_PBF.getDescription(), exception);
        }

        if (lineDelimitedGeojsonOutput)
        {
            countryAtlasShardsRDD.saveAsHadoopFile(
                    getAlternateSubFolderOutput(output, LINE_DELIMITED_GEOJSON_STATISTICS_FOLDER),
                    Text.class, String.class, MultipleLineDelimitedGeojsonOutputFormat.class,
                    new JobConf(configuration()));
            logger.info("\n\n********** SAVED THE LINE DELIMITED GEOJSON ATLAS **********\n");
        }

        // Remove the sliced atlas RDD from cache since we've cached the final RDD
        try
        {
            fullySlicedRawAtlasShardsRDD.unpersist();
        }
        catch (final Exception exception)
        {
            logger.warn(EXCEPTION_MESSAGE,
                    AtlasGeneratorJobGroup.WAY_SECTIONED_PBF.getDescription(), exception);
        }
        // Create the metrics
        final JavaPairRDD<String, AtlasStatistics> statisticsRDD = countryAtlasShardsRDD
                .mapToPair(AtlasGeneratorHelper.generateAtlasStatistics(broadcastSharding));
        statisticsRDD.cache();
        saveAsHadoop(statisticsRDD, AtlasGeneratorJobGroup.SHARD_STATISTICS, output);

        // Aggregate the metrics
        final JavaPairRDD<String, AtlasStatistics> reducedStatisticsRDD = statisticsRDD
                .mapToPair(tuple ->
                {
                    final String countryShardName = tuple._1();
                    final String countryName = StringList.split(countryShardName, "_").get(0);
                    return new Tuple2<>(countryName,
                            // Here using NamedAtlasStatistics so the reduceByKey function below can
                            // name what statistic merging failed, if any.
                            new NamedAtlasStatistics(countryName, tuple._2()));
                }).reduceByKey(AtlasGeneratorHelper.reduceAtlasStatistics())
                .mapToPair(tuple -> new Tuple2<>(tuple._1(), tuple._2().getAtlasStatistics()));

        // Save aggregated metrics
        saveAsHadoop(reducedStatisticsRDD, AtlasGeneratorJobGroup.COUNTRY_STATISTICS, output);

        try
        {
            statisticsRDD.unpersist();
        }
        catch (final Exception exception)
        {
            logger.warn(EXCEPTION_MESSAGE,
                    AtlasGeneratorJobGroup.COUNTRY_STATISTICS.getDescription(), exception);
        }

        // Compute the deltas, if needed
        if (!previousOutputForDelta.isEmpty())
        {
            final JavaPairRDD<String, AtlasDelta> deltasRDD = countryAtlasShardsRDD.flatMapToPair(
                    AtlasGeneratorHelper.computeAtlasDelta(sparkContext, previousOutputForDelta));
            saveAsHadoop(deltasRDD, AtlasGeneratorJobGroup.DELTAS, output);
        }

        if (shouldIncludeFilteredOutputConfiguration != null)
        {
            final JavaPairRDD<String, Atlas> subAtlasRDD = countryAtlasShardsRDD.mapToPair(
                    AtlasGeneratorHelper.subatlas(taggableOutputFilter, AtlasCutType.SOFT_CUT))
                    .filter(tuple -> tuple._2() != null);
            saveAsHadoop(subAtlasRDD, AtlasGeneratorJobGroup.TAGGABLE_FILTERED_OUTPUT, output);
        }

        if (configuredOutputFilter != null)
        {
            final JavaPairRDD<String, Atlas> subAtlasRDD = countryAtlasShardsRDD.mapToPair(
                    AtlasGeneratorHelper.subatlas(configuredOutputFilter, AtlasCutType.SOFT_CUT))
                    .filter(tuple -> tuple._2() != null);
            saveAsHadoop(subAtlasRDD, AtlasGeneratorJobGroup.CONFIGURED_FILTERED_OUTPUT, output);
        }

        try
        {
            countryAtlasShardsRDD.unpersist();
        }
        catch (final Exception exception)
        {
            logger.warn(EXCEPTION_MESSAGE, AtlasGeneratorJobGroup.DELTAS.getDescription(),
                    exception);
        }
    }

    @Override
    protected List<String> outputToClean(final CommandMap command)
    {
        final String output = output(command);
        final List<String> staticPaths = super.outputToClean(command);
        for (final AtlasGeneratorJobGroup group : AtlasGeneratorJobGroup.values())
        {
            staticPaths.add(getAlternateSubFolderOutput(output, group.getCacheFolder()));
        }
        return staticPaths;
    }

    @Override
    protected SwitchList switches()
    {
        final SwitchList result = super.switches();
        result.addAll(AtlasGeneratorParameters.switches());
        return result;
    }

    private Sharding atlasSharding(final CommandMap command)
    {
        final String shardingName = (String) command.get(AtlasGeneratorParameters.SHARDING_TYPE);
        return sharding(shardingName, command);
    }

    private CountryBoundaryMap boundaries(final List<String> countries, final CommandMap command)
    {
        final String countryShapes = (String) command.get(AtlasGeneratorParameters.COUNTRY_SHAPES);
        final CountryBoundaryMap boundaries;
        if (countryShapes == null)
        {
            boundaries = persistenceTools()
                    .boundaries((String) command.get(AtlasGeneratorParameters.PBF_PATH));
        }
        else
        {
            boundaries = new CountryBoundaryMapArchiver().read(resource(countryShapes));
        }
        final Predicate<Taggable> shouldAlwaysSlicePredicate;
        final String shouldAlwaysSliceConfiguration = (String) command
                .get(AtlasGeneratorParameters.SHOULD_ALWAYS_SLICE_CONFIGURATION);
        if (shouldAlwaysSliceConfiguration == null)
        {
            shouldAlwaysSlicePredicate = taggable -> false;
        }
        else
        {
            shouldAlwaysSlicePredicate = AtlasGeneratorParameters
                    .getTaggableFilterFrom(resource(shouldAlwaysSliceConfiguration));
        }
        if (!boundaries.hasGridIndex())
        {
            logger.warn(
                    "Given country boundary file didn't have grid index. Initializing grid index for {}.",
                    countries);
            boundaries.initializeGridIndex(new HashSet<>(countries));
        }
        boundaries.setShouldAlwaysSlicePredicate(shouldAlwaysSlicePredicate);
        return boundaries;
    }

    private Sharding pbfSharding(final CommandMap command)
    {
        final String pbfShardingName = (String) command.get(AtlasGeneratorParameters.PBF_SHARDING);
        try
        {
            return sharding(pbfShardingName, command);
        }
        catch (final Exception e)
        {
            logger.warn("PBF Sharding unavailable, defaulting to atlas sharding.");
        }
        return atlasSharding(command);
    }

    private PersistenceTools persistenceTools()
    {
        return new PersistenceTools(configurationMap());
    }

    private void saveAsHadoop(final JavaPairRDD<?, ?> atlasRDD, final AtlasGeneratorJobGroup group,
            final String output)
    {
        this.getContext().setJobGroup(group.getId().toString(), group.getDescription());
        atlasRDD.saveAsHadoopFile(getAlternateSubFolderOutput(output, group.getCacheFolder()),
                Text.class, group.getKeyClass(), group.getOutputClass(),
                new JobConf(configuration()));
        logger.info(SAVED_MESSAGE, group.getDescription());
    }

    private Sharding sharding(final String shardingName, final CommandMap command)
    {
        if (shardingName == null)
        {
            return persistenceTools()
                    .sharding((String) command.get(AtlasGeneratorParameters.PBF_PATH));
        }
        else
        {
            return AtlasSharding.forString(shardingName, configuration());
        }
    }
}
