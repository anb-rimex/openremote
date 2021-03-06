package org.openremote.manager.datapoint;

import org.hibernate.Session;
import org.hibernate.jdbc.AbstractReturningWork;
import org.openremote.container.Container;
import org.openremote.container.ContainerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.timer.TimerService;
import org.openremote.container.web.WebService;
import org.openremote.manager.asset.AssetProcessingException;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.asset.AssetUpdateProcessor;
import org.openremote.manager.concurrent.ManagerExecutorService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetAttribute;
import org.openremote.model.asset.AssetMeta;
import org.openremote.model.attribute.AttributeEvent.Source;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.datapoint.AssetDatapoint;
import org.openremote.model.datapoint.Datapoint;
import org.openremote.model.datapoint.DatapointInterval;
import org.openremote.model.datapoint.NumberDatapoint;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.BaseAssetQuery;
import org.openremote.model.query.filter.AttributeMetaPredicate;
import org.openremote.model.value.Values;
import org.postgresql.util.PGInterval;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

/**
 * Store and retrieve datapoints for asset attributes and periodically purge data points based on
 * {@link org.openremote.model.asset.AssetMeta#DATA_POINTS_MAX_AGE_DAYS} {@link org.openremote.model.attribute.MetaItem}
 * and {@link #DATA_POINTS_MAX_AGE_DAYS} setting; storage duration defaults to {@value #DATA_POINTS_MAX_AGE_DAYS_DEFAULT}
 * days.
 */
public class AssetDatapointService implements ContainerService, AssetUpdateProcessor {

    public static final String DATA_POINTS_MAX_AGE_DAYS = "DATA_POINTS_MAX_AGE_DAYS";
    public static final String DATA_POINTS_MAX_AGE_DAYS_DEFAULT = "30";
    private static final Logger LOG = Logger.getLogger(AssetDatapointService.class.getName());
    protected PersistenceService persistenceService;
    protected AssetStorageService assetStorageService;
    protected TimerService timerService;
    protected ManagerExecutorService managerExecutorService;
    protected int maxDatapointAgeDays;
    protected ScheduledFuture dataPointsPurgeScheduledFuture;

    @Override
    public void init(Container container) throws Exception {
        persistenceService = container.getService(PersistenceService.class);
        assetStorageService = container.getService(AssetStorageService.class);
        timerService = container.getService(TimerService.class);
        managerExecutorService = container.getService(ManagerExecutorService.class);

        container.getService(WebService.class).getApiSingletons().add(
                new AssetDatapointResourceImpl(
                        container.getService(TimerService.class),
                        container.getService(ManagerIdentityService.class),
                        container.getService(AssetStorageService.class),
                        this
                )
        );

        maxDatapointAgeDays = Integer.parseInt(
                container.getConfig().getOrDefault(DATA_POINTS_MAX_AGE_DAYS, DATA_POINTS_MAX_AGE_DAYS_DEFAULT)
        );

        if (maxDatapointAgeDays <= 0) {
            LOG.warning(DATA_POINTS_MAX_AGE_DAYS + " value is not a valid value so data points won't be auto purged");
        }
    }

    @Override
    public void start(Container container) throws Exception {
        if (maxDatapointAgeDays > 0) {
            long period = 24L * 3600L * 1000L;

            dataPointsPurgeScheduledFuture = managerExecutorService.scheduleAtFixedRate(
                    this::purgeDataPoints,
                    getFirstRunMillis(timerService.getNow()),
                    period);

        }
    }

    @Override
    public void stop(Container container) throws Exception {
        if (dataPointsPurgeScheduledFuture != null) {
            dataPointsPurgeScheduledFuture.cancel(true);
        }
    }

    @Override
    public boolean processAssetUpdate(EntityManager em,
                                      Asset asset,
                                      AssetAttribute attribute,
                                      Source source) throws AssetProcessingException {
        if (Datapoint.isDatapointsCapable(attribute)
                && attribute.isStoreDatapoints()
                && attribute.getStateEvent().isPresent()
                && attribute.getStateEvent().get().getValue().isPresent()) { // Don't store datapoints with null value
            LOG.finest("Storing datapoint for: " + attribute);
            AssetDatapoint assetDatapoint = new AssetDatapoint(attribute.getStateEvent().get());
            em.persist(assetDatapoint);
        }
        return false;
    }

    public List<AssetDatapoint> getDatapoints(AttributeRef attributeRef) {
        return persistenceService.doReturningTransaction(entityManager ->
                entityManager.createQuery(
                        "select dp from AssetDatapoint dp " +
                                "where dp.entityId = :assetId " +
                                "and dp.attributeName = :attributeName " +
                                "order by dp.timestamp desc",
                        AssetDatapoint.class)
                        .setParameter("assetId", attributeRef.getEntityId())
                        .setParameter("attributeName", attributeRef.getAttributeName())
                        .getResultList());
    }


    public long getDatapointsCount() {
        return getDatapointsCount(null);
    }

    public long getDatapointsCount(AttributeRef attributeRef) {
        return persistenceService.doReturningTransaction(entityManager -> {

            String queryStr = attributeRef == null ?
                    "select count(dp) from AssetDatapoint dp" :
                    "select count(dp) from AssetDatapoint dp where dp.entityId = :assetId and dp.attributeName = :attributeName";

            TypedQuery<Long> query = entityManager.createQuery(
                    queryStr,
                    Long.class);

            if (attributeRef != null) {
                query
                        .setParameter("assetId", attributeRef.getEntityId())
                        .setParameter("attributeName", attributeRef.getAttributeName());
            }

            return query.getSingleResult();
        });
    }

    public NumberDatapoint[] aggregateDatapoints(AssetAttribute attribute,
                                                 DatapointInterval datapointInterval,
                                                 long timestamp) {
        LOG.fine("Aggregating datapoints for: " + attribute);

        AttributeRef attributeRef = attribute.getReferenceOrThrow();

        return persistenceService.doReturningTransaction(entityManager ->
                entityManager.unwrap(Session.class).doReturningWork(new AbstractReturningWork<NumberDatapoint[]>() {
                    @Override
                    public NumberDatapoint[] execute(Connection connection) throws SQLException {

                        String truncateX;
                        String step;
                        String interval;
                        Function<Timestamp, String> labelFunction;

                        SimpleDateFormat dayFormat = new SimpleDateFormat("dd. MMM yyyy");
                        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
                        switch (datapointInterval) {
                            case HOUR:
                                truncateX = "minute";
                                step = "1 minute";
                                interval = "1 hour";
                                labelFunction = timeFormat::format;
                                break;
                            case DAY:
                                truncateX = "hour";
                                step = "1 hour";
                                interval = "1 day";
                                labelFunction = timeFormat::format;
                                break;
                            case WEEK:
                                truncateX = "day";
                                step = "1 day";
                                interval = "7 day";
                                labelFunction = dayFormat::format;
                                break;
                            case MONTH:
                                truncateX = "day";
                                step = "1 day";
                                interval = "1 month";
                                labelFunction = dayFormat::format;
                                break;
                            case YEAR:
                                truncateX = "month";
                                step = "1 month";
                                interval = "1 year";
                                labelFunction = dayFormat::format;
                                break;
                            default:
                                throw new IllegalArgumentException("Can't handle interval: " + datapointInterval);
                        }

                        StringBuilder query = new StringBuilder();

                        query.append("select TS as X, coalesce(AVG_VALUE, null) as Y " +
                                " from ( " +
                                "       select date_trunc(?, GS)::timestamp TS " +
                                "       from generate_series(to_timestamp(?) - ?, to_timestamp(?), ?) GS " +
                                "       ) TS " +
                                "  left join ( " +
                                "       select " +
                                "           date_trunc(?, to_timestamp(TIMESTAMP / 1000))::timestamp as TS, ");

                        switch (attribute.getTypeOrThrow().getValueType()) {
                            case NUMBER:
                                query.append(" AVG(VALUE::text::numeric) as AVG_VALUE ");
                                break;
                            case BOOLEAN:
                                query.append(" AVG(case when VALUE::text::boolean is true then 1 else 0 end) as AVG_VALUE ");
                                break;
                            default:
                                throw new IllegalArgumentException("Can't aggregate number datapoints for type of: " + attribute);
                        }

                        query.append(" from ASSET_DATAPOINT " +
                                "         where " +
                                "           to_timestamp(TIMESTAMP / 1000) >= to_timestamp(?) - ? " +
                                "           and " +
                                "           to_timestamp(TIMESTAMP / 1000) <= to_timestamp(?) " +
                                "           and " +
                                "           ENTITY_ID = ? and ATTRIBUTE_NAME = ? " +
                                "         group by TS " +
                                "  ) DP using (TS) " +
                                " order by TS asc "
                        );

                        try (PreparedStatement st = connection.prepareStatement(query.toString())) {

                            long timestampSeconds = timestamp / 1000;
                            st.setString(1, truncateX);
                            st.setLong(2, timestampSeconds);
                            st.setObject(3, new PGInterval(interval));
                            st.setLong(4, timestampSeconds);
                            st.setObject(5, new PGInterval(step));
                            st.setString(6, truncateX);
                            st.setLong(7, timestampSeconds);
                            st.setObject(8, new PGInterval(interval));
                            st.setLong(9, timestampSeconds);
                            st.setString(10, attributeRef.getEntityId());
                            st.setString(11, attributeRef.getAttributeName());

                            try (ResultSet rs = st.executeQuery()) {
                                List<NumberDatapoint> result = new ArrayList<>();
                                while (rs.next()) {
                                    String label = labelFunction.apply(rs.getTimestamp(1));
                                    Number value = rs.getObject(2) != null ? rs.getDouble(2) : null;
                                    result.add(new NumberDatapoint(label, value));
                                }
                                return result.toArray(new NumberDatapoint[result.size()]);
                            }
                        }
                    }
                })
        );
    }

    protected void purgeDataPoints() {
        LOG.info("Starting data points purge daily task");

        // Get list of attributes that have custom durations
        List<Asset> assets = assetStorageService.findAll(
                new AssetQuery()
                        .attributeMeta(
                                new AttributeMetaPredicate(AssetMeta.DATA_POINTS_MAX_AGE_DAYS),
                                new AttributeMetaPredicate(AssetMeta.STORE_DATA_POINTS))
                        .select(new BaseAssetQuery.Select(BaseAssetQuery.Include.ONLY_ID_AND_NAME_AND_ATTRIBUTES)));

        List<AssetAttribute> attributes = assets.stream()
                .map(asset -> asset
                        .getAttributesStream()
                        .filter(assetAttribute ->
                                assetAttribute.isStoreDatapoints()
                                        && assetAttribute.hasMetaItem(AssetMeta.DATA_POINTS_MAX_AGE_DAYS))
                        .collect(toList()))
                .flatMap(List::stream)
                .collect(toList());

        // Purge data points not in the above list using default duration
        LOG.fine("Purging data points of attributes that use default max age days of " + maxDatapointAgeDays);

        persistenceService.doTransaction(em -> em.createQuery(
                "delete from AssetDatapoint dp " +
                        "where dp.timestamp < :dt" + buildWhereClause(attributes, true)
        ).setParameter("dt", 1000L * timerService.getNow().truncatedTo(DAYS).minus(maxDatapointAgeDays, DAYS).getEpochSecond()).executeUpdate());

        if (!attributes.isEmpty()) {
            // Purge data points that have specific age constraints
            Map<Integer, List<AssetAttribute>> ageAttributeRefMap = attributes.stream()
                    .collect(groupingBy(attribute ->
                            attribute
                                    .getMetaItem(AssetMeta.DATA_POINTS_MAX_AGE_DAYS)
                                    .flatMap(metaItem ->
                                            Values.getIntegerCoerced(metaItem.getValue().orElse(null)))
                                    .orElse(maxDatapointAgeDays)));

            ageAttributeRefMap.forEach((age, attrs) -> {
                LOG.fine("Purging data points of " + attrs.size() + " attributes that use a max age of " + age);

                try {
                    persistenceService.doTransaction(em -> em.createQuery(
                            "delete from AssetDatapoint dp " +
                                    "where dp.timestamp < :dt" + buildWhereClause(attrs, false)
                    ).setParameter("dt", 1000L * timerService.getNow().truncatedTo(DAYS).minus(age, DAYS).getEpochSecond()).executeUpdate());
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "An error occurred whilst deleting data points, this should not happen", e);
                }
            });
        }

        LOG.info("Finished data points purge daily task");
    }

    protected String buildWhereClause(List<AssetAttribute> attributes, boolean negate) {

        if (attributes.isEmpty()) {
            return "";
        }

        String whereStr = attributes.stream()
                .map(assetAttribute -> {
                    AttributeRef attributeRef = assetAttribute.getReferenceOrThrow();
                    return "('" + attributeRef.getEntityId() + "','" + attributeRef.getAttributeName() + "')";
                })
                .collect(Collectors.joining(","));

        return " and (dp.entityId, dp.attributeName) " + (negate ? "not " : "") + "in (" + whereStr + ")";
    }

    protected long getFirstRunMillis(Instant currentTime) {
        // Schedule purge at approximately 3AM daily
        return ChronoUnit.MILLIS.between(
                currentTime,
                currentTime.truncatedTo(DAYS).plus(27, ChronoUnit.HOURS));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                '}';
    }
}
