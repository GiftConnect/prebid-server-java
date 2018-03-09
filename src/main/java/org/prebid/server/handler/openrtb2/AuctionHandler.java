package org.prebid.server.handler.openrtb2;

import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.response.BidResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;
import org.prebid.server.auction.AuctionRequestFactory;
import org.prebid.server.auction.ExchangeService;
import org.prebid.server.cookie.UidsCookie;
import org.prebid.server.cookie.UidsCookieService;
import org.prebid.server.exception.InvalidRequestException;
import org.prebid.server.execution.GlobalTimeout;
import org.prebid.server.metric.MetricName;
import org.prebid.server.metric.Metrics;
import org.prebid.server.util.HttpUtil;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class AuctionHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(AuctionHandler.class);

    private static final Clock CLOCK = Clock.systemDefaultZone();

    private final long defaultTimeout;
    private final ExchangeService exchangeService;
    private final AuctionRequestFactory auctionRequestFactory;
    private final UidsCookieService uidsCookieService;
    private final Metrics metrics;

    public AuctionHandler(long defaultTimeout, ExchangeService exchangeService,
                          AuctionRequestFactory auctionRequestFactory, UidsCookieService uidsCookieService,
                          Metrics metrics) {
        this.defaultTimeout = defaultTimeout;
        this.exchangeService = Objects.requireNonNull(exchangeService);
        this.auctionRequestFactory = Objects.requireNonNull(auctionRequestFactory);
        this.uidsCookieService = Objects.requireNonNull(uidsCookieService);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public void handle(RoutingContext context) {
        // Prebid Server interprets request.tmax to be the maximum amount of time that a caller is willing to wait
        // for bids. However, tmax may be defined in the Stored Request data.
        // If so, then the trip to the backend might use a significant amount of this time. We can respect timeouts
        // more accurately if we note the real start time, and use it to compute the auction timeout.
        final long startTime = CLOCK.millis();

        final boolean isSafari = HttpUtil.isSafari(context.request().headers().get(HttpHeaders.USER_AGENT));

        updateRequestMetrics(isSafari);

        final UidsCookie uidsCookie = uidsCookieService.parseFromRequest(context);

        auctionRequestFactory.fromRequest(context)
                .recover(this::updateErrorRequestsMetric)
                .map(bidRequest -> updateAppAndNoCookieMetrics(bidRequest, uidsCookie.hasLiveUids(), isSafari))
                .compose(bidRequest -> exchangeService.holdAuction(bidRequest, uidsCookie,
                        timeout(bidRequest, startTime)))
                .setHandler(responseResult -> handleResult(responseResult, context));
    }

    private GlobalTimeout timeout(BidRequest bidRequest, long startTime) {
        final Long tmax = bidRequest.getTmax();
        return GlobalTimeout.create(startTime, tmax != null && tmax > 0 ? tmax : defaultTimeout);
    }

    private void handleResult(AsyncResult<BidResponse> responseResult, RoutingContext context) {
        if (responseResult.succeeded()) {
            context.response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
                    .end(Json.encode(responseResult.result()));
        } else {
            final Throwable exception = responseResult.cause();
            if (exception instanceof InvalidRequestException) {
                final List<String> messages = ((InvalidRequestException) exception).getMessages();
                logger.info("Invalid request format: {0}", messages);
                context.response()
                        .setStatusCode(HttpResponseStatus.BAD_REQUEST.code())
                        .end(messages.stream().map(m -> String.format("Invalid request format: %s", m))
                                .collect(Collectors.joining("\n")));
            } else {
                logger.error("Critical error while running the auction", exception);
                context.response()
                        .setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code())
                        .end(String.format("Critical error while running the auction: %s", exception.getMessage()));
            }
        }
    }

    private void updateRequestMetrics(boolean isSafari) {
        metrics.incCounter(MetricName.requests);
        metrics.incCounter(MetricName.open_rtb_requests);
        if (isSafari) {
            metrics.incCounter(MetricName.safari_requests);
        }
    }

    private Future<BidRequest> updateErrorRequestsMetric(Throwable failed) {
        metrics.incCounter(MetricName.error_requests);
        return Future.failedFuture(failed);
    }

    private BidRequest updateAppAndNoCookieMetrics(BidRequest bidRequest, boolean isLifeSync, boolean isSafari) {
        if (bidRequest.getApp() != null) {
            metrics.incCounter(MetricName.app_requests);
        } else if (isLifeSync) {
            metrics.incCounter(MetricName.no_cookie_requests);
            if (isSafari) {
                metrics.incCounter(MetricName.safari_no_cookie_requests);
            }
        }

        return bidRequest;
    }
}
