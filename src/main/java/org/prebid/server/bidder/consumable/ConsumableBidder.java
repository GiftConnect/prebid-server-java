package org.prebid.server.bidder.consumable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.Strings;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.MultiMap;
import org.apache.commons.collections4.CollectionUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderCall;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.CompositeBidderResponse;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.model.UpdateResult;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.consumable.ExtImpConsumable;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebid;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebidVideo;
import org.prebid.server.util.BidderUtil;
import org.prebid.server.util.HttpUtil;
import org.prebid.server.util.ObjectUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ConsumableBidder implements Bidder<BidRequest> {

    private static final String OPENRTB_VERSION = "2.5";
    private static final TypeReference<ExtPrebid<?, ExtImpConsumable>> CONS_EXT_TYPE_REFERENCE = new TypeReference<>() {
    };
    public static final String SITE_URI_PATH = "/sb/rtb";
    public static final String APP_URI_PATH = "/rtb/bid?s=";
    private final JacksonMapper mapper;

    private final String endpointUrl;

    public ConsumableBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = endpointUrl;
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest bidRequest) {
        final List<Imp> imps = new ArrayList<>();
        final List<BidderError> errors = new ArrayList<>();
        final List<HttpRequest<BidRequest>> httpRequests = new ArrayList<>();
        String placementId = null;
        for (Imp imp : bidRequest.getImp()) {
            try {
                final ExtImpConsumable impExt = parseImpExt(imp);
                if (!isImpValid(bidRequest.getSite(), bidRequest.getApp(), impExt)) {
                    continue;
                }
                if (Strings.isNullOrEmpty(placementId) && !Strings.isNullOrEmpty(impExt.getPlacementId())) {
                    placementId = impExt.getPlacementId();
                }

                imps.add(modifyImp(imp, impExt));

            } catch (PreBidException e) {
                errors.add(BidderError.badInput(e.getMessage()));
            }
        }
        if (imps.isEmpty()) {
            return Result.withErrors(errors);
        }
        final BidRequest modRequest = modifyBidRequest(bidRequest, imps);
        final String finalUrl = constructUri(placementId);
        httpRequests.add(BidderUtil.defaultRequest(modRequest, resolveHeaders(), finalUrl, mapper));
        return Result.of(httpRequests, errors);
    }

    private ExtImpConsumable parseImpExt(Imp imp) {
        try {
            return mapper.mapper().convertValue(imp.getExt(), CONS_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage());
        }
    }

    private boolean isImpValid(Site site, App app, ExtImpConsumable impExt) {
        return ((app != null && !Strings.isNullOrEmpty(impExt.getPlacementId()))
                || (site != null && impExt.getSiteId() != 0 && impExt.getNetworkId() != 0 && impExt.getUnitId() != 0));

    }

    private Imp modifyImp(Imp imp, ExtImpConsumable impExt) {
        final UpdateResult<ObjectNode> impExtUpdateResult = modifyImpExt(imp, impExt);

        return imp;
    }

    private UpdateResult<ObjectNode> modifyImpExt(Imp imp, ExtImpConsumable extImpConsumable) {
        final Integer siteId = extImpConsumable.getSiteId();
        final String unitName = extImpConsumable.getUnitName();
        final Integer unitId = extImpConsumable.getUnitId();
        final Integer networkId = extImpConsumable.getNetworkId();
        final String placementId = extImpConsumable.getPlacementId();
        final ObjectNode impExt = imp.getExt();

        final ObjectNode updatedExt = impExt.deepCopy();
        if (Strings.isNullOrEmpty(placementId)) {
            updatedExt.set("siteId", TextNode.valueOf(Integer.toString(siteId)));
            updatedExt.set("unitName", TextNode.valueOf(unitName));
            updatedExt.set("unitId", TextNode.valueOf(Integer.toString(unitId)));
            updatedExt.set("networkId", TextNode.valueOf(Integer.toString(networkId)));
        } else {
            updatedExt.set("placementId", TextNode.valueOf(placementId));
        }

        return UpdateResult.updated(updatedExt);
    }

    private BidRequest modifyBidRequest(BidRequest bidRequest, List<Imp> imps) {
        return bidRequest.toBuilder().imp(imps).build();
    }

    private String constructUri(String placementId) {
        final String uri = Strings.isNullOrEmpty(placementId) ? SITE_URI_PATH : (APP_URI_PATH + placementId);
        return this.endpointUrl + uri;
    }

    private static MultiMap resolveHeaders() {
        return HttpUtil.headers().add(HttpUtil.X_OPENRTB_VERSION_HEADER, OPENRTB_VERSION);
    }

    @Override
    @Deprecated(since = "Not used, since Bidder.makeBidderResponse(...) was overridden.")
    public Result<List<BidderBid>> makeBids(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {
        return Result.withError(BidderError.generic("Invalid method call"));
    }

    @Override
    public CompositeBidderResponse makeBidderResponse(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            final List<BidderError> bidderErrors = new ArrayList<>();
            return CompositeBidderResponse.builder().bids(extractConsumableBids(bidRequest, bidResponse, bidderErrors))
                    .errors(bidderErrors).build();
        } catch (DecodeException e) {
            return CompositeBidderResponse.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private List<BidderBid> extractConsumableBids(BidRequest bidRequest, BidResponse bidResponse,
                                                  List<BidderError> bidderErrors) {
        return bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid()) ? Collections.emptyList()
                : bidsFromResponse(bidResponse, bidRequest, bidderErrors);
    }

    private List<BidderBid> bidsFromResponse(BidResponse bidResponse, BidRequest bidRequest, List<BidderError> errors) {
        return bidResponse.getSeatbid().stream().filter(Objects::nonNull).map(SeatBid::getBid).filter(Objects::nonNull)
                .flatMap(Collection::stream).map(bid -> toBidderBid(bid, bidRequest, bidResponse, errors))
                .filter(Objects::nonNull).toList();
    }

    private BidderBid toBidderBid(Bid bid, BidRequest bidRequest, BidResponse bidResponse, List<BidderError> errors) {
        final BidType bidType;
        try {
            bidType = getBidType(bid, bidRequest.getImp());
        } catch (PreBidException e) {
            errors.add(BidderError.badServerResponse(e.getMessage()));
            return null;
        }

        final Bid updatedBid = (bidType.equals(BidType.video))?updateBidWithVideoAttributes(bid):bid;

        return BidderBid.of(updatedBid, bidType, bidResponse.getCur());
    }

    private static BidType getBidType(Bid bid, List<Imp> imps) {
        return getBidTypeFromMtype(bid.getMtype()).or(() -> getBidTypeFromExtPrebidType(bid.getExt()))
                .orElseGet(() -> getBidTypeFromImp(imps, bid.getImpid()));
    }

    private static Optional<BidType> getBidTypeFromMtype(Integer mType) {
        final BidType bidType = mType != null ? switch (mType) {
            case 1 -> BidType.banner;
            case 2 -> BidType.video;
            case 3 -> BidType.audio;
            case 4 -> BidType.xNative;
            default -> null;
        } : null;

        return Optional.ofNullable(bidType);
    }

    private static Optional<BidType> getBidTypeFromExtPrebidType(ObjectNode bidExt) {
        return Optional.ofNullable(bidExt).map(ext -> ext.get("prebid")).map(prebid -> prebid.get("type"))
                .map(JsonNode::asText).map(BidType::fromString);
    }

    private static BidType getBidTypeFromImp(List<Imp> imps, String impId) {
        for (Imp imp : imps) {
            if (imp.getId().equals(impId)) {
                if (imp.getBanner() != null) {
                    return BidType.banner;
                } else if (imp.getVideo() != null) {
                    return BidType.video;
                } else if (imp.getXNative() != null) {
                    return BidType.xNative;
                } else if (imp.getAudio() != null) {
                    return BidType.audio;
                }
            }
        }
        throw new PreBidException("Unmatched impression id " + impId);
    }

    private Bid updateBidWithVideoAttributes(Bid bid) {
        final ObjectNode bidExt = bid.getExt();
        final ExtBidPrebid extPrebid = bidExt != null ? parseBidExt(bidExt) : ExtBidPrebid.builder()
                .video(ExtBidPrebidVideo.of(null, null)).build();
        final ExtBidPrebidVideo extVideo = extPrebid != null ? extPrebid.getVideo() : null;
        final Bid updatedBid;
        if (extVideo != null) {
            updatedBid = bid.toBuilder().ext(resolveBidExt(bid.getDur())).build();
        } else {
            updatedBid = bid;
        }
        return updatedBid;
    }

    private ExtBidPrebid parseBidExt(ObjectNode bidExt) {
        try {
            return mapper.mapper().treeToValue(bidExt, ExtBidPrebid.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private ObjectNode resolveBidExt(Integer duration) {
        return mapper.mapper().valueToTree(ExtBidPrebid.builder().video(ExtBidPrebidVideo
                .of(duration, null)).build());
    }

}
