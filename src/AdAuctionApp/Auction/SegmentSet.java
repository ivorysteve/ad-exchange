/**
 * Part of a source code package originally written for the AdAuctionApp project.
 * Intended for use as a programming work sample file only.  Not for distribution.
 **/
package AdAuctionApp.Auction;

import java.io.PrintWriter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import AdAuctionApp.Auction.Auctioneer;
import AdAuctionApp.Auction.AuctionConstants.WinType;
import AdAuctionApp.Cache.Central.AuctionObject;
import AdAuctionApp.Cache.Central.CampaignBuyAuctionInfo;
import AdAuctionApp.Cache.Central.PlacementAttribute;
import AdAuctionApp.Cache.Central.Spot;
import AdAuctionApp.Core.AdExchangeConstants;
import AdAuctionApp.Core.Money;

/**
 * The set of avails and bids for a segmented avail set.
 *
 * An available exchange (the 'root avail') may be split into
 * segments, and in order to be able to sell this original avail, some
 * combination of segments that add up to the entire root's duration
 * must be won together.
 *
 * The algorithm maximizes the sum of the ranks of the segments.
 * All auction processing specific to segmented avails are done
 * by this class.
 * Currently, only 60, 30, and 15 second segments are supported, with
 * valid combinations being 1 60-second OR 2 30-seconds OR 1 30-second and
 * 2 15-second segments.
 */
public class SegmentSet
{
    /** Algorithms for determining segment winners. */
    public enum WinMethod
    {
        TOP_RANKED,
        LINCHPIN,
        SIMPLE,
        COMPLEX
    }

    /**
     * Set which algorithm is used to determine a SegmentSet winner.
     * Will apply to both real and simulated auctions.
     * @param algorithm
     */
    public static void setWinAlgorithm(WinMethod algorithm)
    {
        theWinAlgorithm = algorithm;
    }

    /**
     * Simple (non-debug) constructor.
     * @param rootAvail Root Avail of this Segment.
     */
    public SegmentSet(Spot rootAvail)
    {
        this(rootAvail, null, null);
    }

    /**
     * Constructor.
     * @param rootAvail Root Avail of this Segment.
     * @param debugActions a potentially null list to store the
     * DebugActions.  If not null, we'll store each action on this
     * segmentSet in the debugAction list for debugging.  Then, a call
     * to dumpDebugActions() will return the actions as a string.
     * @param segmentStats a potentially null SegmentStats object used
     * for storing the counts of various actions performed on the
     * segment set.
     */
    public SegmentSet(Spot rootAvail,
            List<DebugAction> debugActions,
            SegmentStats segmentStats)
    {
        myRootAvail = rootAvail;
        myDebugActions = debugActions;
        myWinAlgorithm = theWinAlgorithm;
        myDoDebug = (myDebugActions != null);
        mySegmentStats = segmentStats;

        if (myRootAvail.duration == 60)
        {
            myPairDuration = 30;
            myQuadDuration = 15;
        }
        else if (myRootAvail.duration == 30)
        {
            myPairDuration = 15;
            myQuadDuration = NO_DURATION;
        }
        else
        {
            myPairDuration = NO_DURATION;
            myQuadDuration = NO_DURATION;
        }

        // Temporary list of all bids.
        myTmpAllBidList = new ArrayList<AuctionObjectShadow>();

        // The bids will be ordered as we add them, avoiding an expensive sort.
        myTmpRoot   = new ArrayList<AuctionObjectShadow>();
        myTmpPair_0 = new ArrayList<AuctionObjectShadow>();
        myTmpPair_1 = new ArrayList<AuctionObjectShadow>();
        myTmpQuad_0 = new ArrayList<AuctionObjectShadow>();
        myTmpQuad_1 = new ArrayList<AuctionObjectShadow>();

        if (mySegmentStats != null)
            mySegmentStats.SegmentCounter++;
    }

    /**
     * Append a list of bids to a StringBuilder.
     * @param list
     * @param sb
     * @param label Initial label.
     */
    private void appendBidList(List<IndxBid> list, StringBuilder sb,
            String label)
    {
        if (list != null)
        {
            sb.append("\n").append(label).
            append("[").append((list==null?0:list.size())).
            append("]: ");
            for (IndxBid ib : list)
            {
                // (123|2345|0.0|234.45)
                AuctionObject ao = ib.bid.auctionObj;
                String winTag =
                        (ib.bid.auctionState==AuctionStatus.WINNER?"*":"");
                sb.append(winTag).
                append("(").
                append(ao.campaignBuy.campaignBuyID).append("|").
                append(ao.spot.id).append("|").
                append(ao.rank()).append("|").
                append(ib.bid.auctionCost()).
                append(") ");
            }

            if (!checkBidListOrder(list))
            {
                sb.append("RANKED-LIST-OUT-OF-ORDER");
            }
        }
    }

    /**
     * Check the order of the bids.
     * @return true if the order is good, false if the order is bad.
     */
    private boolean checkBidListOrder(List<IndxBid> list)
    {
        if (list == null)
            return true;

        float prevRank = Float.MAX_VALUE;
        long prevCents = Long.MAX_VALUE;

        for (IndxBid ib : list)
        {
            AuctionObjectShadow aos = ib.bid;
            float thisRank = aos.auctionObj.rank();
            long thisCents = aos.auctionCost().valueInCents();
            if (prevRank == thisRank)
            {
                if (thisCents > prevCents)
                {
                    return false;
                }
            }
            prevRank = thisRank;
            prevCents = thisCents;
        }
        return true;
    }

    /**
     * Add a bid to this set's bids.  Should be called
     * during initialization.
     * @param bid Bid to add to this SegmentSet.
     */
    public void addBid(AuctionObjectShadow bid)
    {
        // Add to complete list
        myTmpAllBidList.add(bid);

        // Add to sorted list for corresponding duration & offset.
        int dur = bid.auctionObj.spot.duration;
        if (dur == myRootAvail.duration)
        {
            myTmpRoot.add(bid);
        }
        else if (dur == myPairDuration)
        {
            if (bid.auctionObj.spot.segmentOffset == 0)
                myTmpPair_0.add(bid);
            else
                myTmpPair_1.add(bid);
        }
        else if (dur == myQuadDuration)
        {
            if (bid.auctionObj.spot.segmentOffset == 0)
                myTmpQuad_0.add(bid);
            else
                myTmpQuad_1.add(bid);
        }
        else
        {
            theLogger.debug("Avail #" + bid.auctionObj.spot.id +
                    ": Unknown spot duration " + dur + " sec.!");
        }
    }

    /**
     * Set the SegmentSet of the mirrored partner of the root avail.
     * @param mSegSet SegmentSet
     */
    public void setMirroredSegmentSet(SegmentSet mSegSet)
    {
        myMirroredSegmentSet = mSegSet;
    }

    /**
     * Get the mirrored partner SegmentSet.
     * @return the mirrored partner SegmentSet or null if none.
     */
    public SegmentSet mirrSegSet()
    {
        return myMirroredSegmentSet;
    }

    /**
     * @param isOK If true, the inventory owner does not
     * require this segmented avail to be completely filled
     * to be considered a winner.
     */
    public void setAllowsPartialWins(boolean isOK)
    {
        myAllowPartialWins = isOK;
    }

    /**
     * Has this SegmentSet been completely populated with winning bids?
     * @return true if SegmentSet has found a complete set of winners.
     */
    public boolean foundAllWinners()
    {
        return myWinDuration >= myRootAvail.duration;
    }

    /**
     * Has 1 or more bids won a portion of this SegmentSet?
     * @return true if 1 or more bids won a portion of this SegmentSet.
     */
    public boolean foundWinner()
    {
        return myWinDuration > 0;
    }

    /**
     * Convert all bids added to this SegmentSet to IndxBids.
     * Must be called after all bids have been added to this
     * SegmentSet via {@link #addBid(AuctionObjectShadow)}.
     * Frees temporary list space.
     */
    public void optimizeArrays()
    {
        // Collect all bids for convenient marking of out-of-play.
        // Sort list so we assign any partial winners in ranked order.
        myAllRankedBids = new ArrayList<AuctionObjectShadow>(myTmpAllBidList);
        Collections.sort(myAllRankedBids, Auctioneer.RANK_WINNER_DESC_COMPARATOR);

        // Convert to IndxBids
        myRankedBidsRoot   = convertBidList(myTmpRoot);
        myRankedBidsPair_0 = convertBidList(myTmpPair_0);
        myRankedBidsPair_1 = convertBidList(myTmpPair_1);
        myRankedBidsQuad_0 = convertBidList(myTmpQuad_0);
        myRankedBidsQuad_1 = convertBidList(myTmpQuad_1);

        // Remove temp lists to free memory.
        myTmpAllBidList = null;
        myTmpRoot = null;
        myTmpPair_0 = null;
        myTmpPair_1 = null;
        myTmpQuad_0 = null;
        myTmpQuad_1 = null;
    }

    /**
     * Reset debug/tracking counters.  Should be called
     * before starting a new auction.
     */
    public static void resetCounters()
    {
        theTstamp = 1;
    }

    /**
     * Convert AOS list to index bid list.  Maintains original order
     * as specified by the Set iterator.
     * @param oList Original bid set
     * @return Index bid list
     */
    private List<IndxBid> convertBidList(List<AuctionObjectShadow> oList)
    {
        List<IndxBid> rtnList = new ArrayList<IndxBid>();
        int i = 0;

        for (AuctionObjectShadow origBid : oList)
        {
            IndxBid bid = new IndxBid(rtnList, origBid, i);
            rtnList.add(bid);
            i++;
        }
        return rtnList;
    }

    /**
     * Mark all other bids on the spots in this SegmentSet
     * as HAS_WINNER.  Assumes WINNER bids have already been set.
     * Also marks this segment as won.
     */
    public void markAllSegmentNonWinners()
    {
        myWinDuration = 0;

        // Some bids may have a status of NO_SEGMENT_COMBO_FOUND because they
        // aren't included in this pass and were previously in pass #0 where
        // they had their status set by the Auctioneer via
        // SegmentSet.markAllSegmentBidsLost()
        for (AuctionObjectShadow bid : myAllRankedBids)
        {
            if (bid.auctionState == AuctionStatus.IN_PLAY ||
                    bid.auctionState == AuctionStatus.NO_SEGMENT_COMBO_FOUND)
            {
                bid.auctionState = AuctionStatus.HAS_SEGMENTED_WINNER;
            }

            if (bid.auctionState == AuctionStatus.WINNER)
            {
                myWinDuration += bid.auctionSpot.spot.duration;
            }
        }
        checkWinDuration();
    }

    /**
     *  This gets called by the Auctioneer after auctioning all
     *  bids for a given pass.  If this SegmentSet has not set
     *  a winner, determine if we can find a winner now.
     *  @param auctioneer the auctioneer
     *  @param pass the pass for this auction.
     */
    public void auctionComplete(Auctioneer auctioneer,
            AuctionPass pass)
    {
        if (!foundWinner())
        {
            // No partial or full winner of this segment set.
            long millis = 0;

            if (mySegmentStats != null)
            {
                millis = System.currentTimeMillis();
            }

            if (mySegmentStats != null)
                mySegmentStats.NumPostAuctionEvaluated++;

            handleSegmentedWinner(auctioneer, null, pass);

            if (foundWinner())
            {
                if (mySegmentStats != null)
                    mySegmentStats.NumPostAuctionWinners++;
            }

            if (mySegmentStats != null)
            {
                mySegmentStats.PostAuctionMillis +=
                        (System.currentTimeMillis() - millis);
            }
        }
    }

    /**
     * Mark all in-play bids on the spots in this SegmentSet
     * as NO_SEGMENT_COMBO_FOUND, taking them out of play.
     * Should be called when all combinations for this SegmentSet
     * have been tried.  Does not overwrite any previous losing reasons.
     */
    public void markAllSegmentBidsLost()
    {
        for (AuctionObjectShadow bid : myAllRankedBids)
        {
            if (bid.auctionState == AuctionStatus.IN_PLAY)
            {
                bid.auctionState = AuctionStatus.NO_SEGMENT_COMBO_FOUND;
            }
        }
    }

    /**
     * A SegmentSet contains ranked lists that are sorted
     * by rank & cost. Since the cost may have changed due to
     * assignCpm we need to re-sort these ranked lists after
     * assignCpm and before the auction (pass).
     */
    public void sortSegment()
    {
        sortBidList(myRankedBidsRoot);
        sortBidList(myRankedBidsPair_0);
        sortBidList(myRankedBidsPair_1);
        sortBidList(myRankedBidsQuad_0);
        sortBidList(myRankedBidsQuad_1);
    }

    private void sortBidList(List<IndxBid> ibids)
    {
        Collections.sort(ibids, RANK_COMPARATOR);

        int i = 0;
        for (IndxBid ibid : ibids)
        {
            ibid.index = i++;
        }
    }

    /**
     * Get the sum of ranks for this SegmentSet currently.
     * Sum the ranks of all winners.
     * @return
     */
    public double segmentSetRankSum()
    {
        double total = 0f;
        for (IndxBid ibid : myWinningBids)
        {
            total += ibid.bid.auctionObj.rank();
        }
        return total;
    }

    /**
     * Find the IndxBid that corresponds to an AuctionObjectShadow.
     * @param bid AuctionObjectShadow to match.
     * @return IndxBid in our list or null if not found.
     */
    public IndxBid findIndxBid(AuctionObjectShadow bid)
    {
        if (bid == null)
            return null;

        int dur = bid.auctionSpot.spot.duration;
        if (dur == myRootAvail.duration)
        {
            return findBidInList(myRankedBidsRoot, bid);
        }
        else if (dur == myPairDuration)
        {
            if (bid.auctionObj.spot.segmentOffset == 0)
                return findBidInList(myRankedBidsPair_0, bid);
            return findBidInList(myRankedBidsPair_1, bid);
        }
        else if (dur == myQuadDuration)
        {
            if (bid.auctionObj.spot.segmentOffset == 0)
                return findBidInList(myRankedBidsQuad_0, bid);
            return findBidInList(myRankedBidsQuad_1, bid);
        }

        throw new IllegalArgumentException("Illegal avail duration " + dur);
    }

    private void checkWinDuration()
    {
        if (myWinDuration > myRootAvail.duration)
        {
            // This should never happen.
            try
            {
                String msg = "checkWinDuration() " +
                        "Combined duration of winners (" +
                        myWinDuration + ") is greater than " +
                        "duration of segment root avail (" +
                        myRootAvail.duration + ") ";

                theLogger.error(msg + this);

                debugMonitor(msg);
            }
            catch (Throwable t)
            {
            }
        }
    }

    /**
     * Given a winner, mark any overlapping bids, and add to
     * the SegmentSet list of winners.  Supports partial
     * SegmentSet wins.
     * in the same segment as losers.
     * @param wbid Winning bid
     */
    private void recordSingleSegmentSetWinner(IndxBid wbid)
    {
        if (wbid == null)
            return;

        // Add to our winners.
        myWinningBids.add(wbid);

        // At least part of the SegmentSet has won.
        myWinDuration += wbid.bid.auctionSpot.spot.duration;

        checkWinDuration();

        // mark any overlapping bids as lost.
        List<IndxBid> olist = wbid.origList;

        // Invalidate all other bids in our own list.
        markSegmentBidsInListLost(olist);

        if (olist == myRankedBidsRoot)
        {
            // A root avail bid won.  Invalidate all overlapping partial bids.
            markSegmentBidsInListLost(myRankedBidsPair_0);
            markSegmentBidsInListLost(myRankedBidsPair_1);
            markSegmentBidsInListLost(myRankedBidsQuad_0);
            markSegmentBidsInListLost(myRankedBidsQuad_1);
        }
        else if (olist == myRankedBidsPair_0 || olist == myRankedBidsPair_1)
        {
            // One of the pair won.  Invalidate all the root avail bids.
            markSegmentBidsInListLost(myRankedBidsRoot);
            // The quads occupy the Pair_0, so if this is a Pair_0 list, invalidate all quads.
            if (olist == myRankedBidsPair_0)
            {
                markSegmentBidsInListLost(myRankedBidsQuad_0);
                markSegmentBidsInListLost(myRankedBidsQuad_1);
            }
        }
        else if (olist == myRankedBidsQuad_0 || olist == myRankedBidsQuad_1)
        {
            // One of the quads won.  Invalidate all root avail bids.
            markSegmentBidsInListLost(myRankedBidsRoot);
            // The quads occupy the Pair_0 slot, so invalidate these as well.
            markSegmentBidsInListLost(myRankedBidsPair_0);
        }
    }

    /**
     * Mark all bids that are still in-play or NO_SEGMENT_COMBO_FOUND
     * in an IndxBid list as HAS_SEGMENTED_WINNER.
     * @param list to mark.
     */
    private void markSegmentBidsInListLost(List<IndxBid> list)
    {
        // Some bids may have a status of NO_SEGMENT_COMBO_FOUND because they
        // aren't included in this pass and were previously in pass #0 where
        // they had their status set by the Auctioneer via
        // SegmentSet.markAllSegmentBidsLost()
        for (IndxBid ib : list)
        {
            if (ib.bid.auctionState == AuctionStatus.IN_PLAY ||
                    ib.bid.auctionState == AuctionStatus.NO_SEGMENT_COMBO_FOUND)
            {
                ib.bid.auctionState = AuctionStatus.HAS_SEGMENTED_WINNER;
            }
        }
    }

    /**
     * Find the IndxBid in a list that corresponds to an AuctionObjectShadow.
     * @param origList List to search
     * @param bid AuctionObjectShadow to match.
     * @return IndxBid in our list or null if not found.
     */
    public IndxBid findBidInList(List<IndxBid> origList,
            AuctionObjectShadow bid)
    {
        for (IndxBid ibid : origList)
        {
            if (ibid.bid == bid)
            {
                return ibid;
            }
        }
        return null;
    }

    /**
     * MAIN ALGORITHM
     * Special processing for a segmented avail bid already judged
     * to be a possible winner.  The bid may be on either a segment
     * parent or one of the child segments.
     * @param wBid Bid to evaluate.  Has already been judged
     * as able to be a winner itself.
     * @param pass Which pass we are executing.
     */
    public void handleSegmentedWinner(Auctioneer auctioneer,
            AuctionObjectShadow winBid, AuctionPass pass)
    {
        long millis = 0;
        if (mySegmentStats != null)
        {
            mySegmentStats.SegmentsEvaluated++;
            millis = System.currentTimeMillis();
        }

        // XXX: If we keep this, this should be implemented with subclassing...
        switch (myWinAlgorithm)
        {
        case COMPLEX:
            handleSegmentedWinnerComplex(auctioneer, winBid, pass);
            break;
        case LINCHPIN:
            handleSegmentedWinnerLinchpinOnly(auctioneer, winBid, pass);
            break;
        case TOP_RANKED:
            handleSegmentedWinnerTopRankedOnly(auctioneer, winBid, pass);
            break;
        case SIMPLE:
            handleSegmentedWinnerSimple(auctioneer, winBid, pass);
            break;
        }

        checkBudget();

        if (mySegmentStats != null)
        {
            mySegmentStats.SegmentEvalMillis +=
                    (System.currentTimeMillis() - millis);
        }
    }

    /**
     * SIMPLE VERSION 1 OF ALGORITHM for segment handling.
     * Only award a winner if winning bid is in highest-ranked Combo.
     * We only evaluate one Combo.
     * @param linchpin Bid to evaluate.  Has already been judged
     * as able to be a winner itself.
     * @param pass Which pass we are executing.
     */
    public void handleSegmentedWinnerTopRankedOnly(Auctioneer auctioneer,
            AuctionObjectShadow linchpin, AuctionPass pass)
    {
        ComboSet combos = new ComboSet(linchpin, pass);
        Combo topCombo = combos.topRanked;
        if (!topCombo.containsLinchpin())
        {
            // Highest-ranked combo does not include this bid.
            // Fail, but do not mark.  Do nothing.
            return;
        }
        if (!topCombo.isValidCombo())
        {
            // Although being top-ranked, the combo could not find enough bids.
            // Fail but do nothing.
            return;
        }

        boolean gotWinner = false;

        // Evaluate all bids in our highest-ranked Combo.
        while (!gotWinner)
        {
            gotWinner = topCombo.canAllWin(auctioneer, pass, linchpin);

            if (mySegmentStats != null)
                mySegmentStats.ComboEvalCount++;

            if (gotWinner)
            {
                break;
            }
            boolean gotNext = topCombo.next();
            if (!gotNext)
            {
                // No more bids to try.
                break;
            }

        } // End evaluation loop

        // Record winner if we found one.
        if (gotWinner)
        {
            recordComboWinner(auctioneer, pass, topCombo);
        }
        // Done

    } // handleSegmentedWinnerTopRankedOnly()

    /**
     * SIMPLE VERSION 2 OF ALGORITHM for segment handling.
     * Award winner to highest-ranked Combo that contains linchpin.
     * We only evaluate one Combo here.
     * @param wBid Bid to evaluate.  Has already been judged
     * as able to be a winner itself.
     * @param pass Which pass we are executing.
     */
    public void handleSegmentedWinnerLinchpinOnly(Auctioneer auctioneer,
            AuctionObjectShadow linchpin, AuctionPass pass)
    {
        if (linchpin == null)
        {
            // This algorithm does not support a null linchpin.
            return;
        }

        // Create ComboSet from current auction state.
        ComboSet combos = new ComboSet(linchpin, pass);

        // Find highest-ranked Combo that contains linchpin.
        Combo topCombo = null;
        if (linchpin.auctionObj.spot.duration == myRootAvail.duration)
        {
            topCombo = combos.comboRoot;
        }
        else if (linchpin.auctionObj.spot.duration == myPairDuration)
        {
            double rTrio = combos.comboTrio.rank();
            double rPair = combos.comboPair.rank();
            topCombo = (rPair > rTrio ? combos.comboPair : combos.comboTrio);
        }
        else
        {
            topCombo = combos.comboTrio;
        }

        if (topCombo==null || !topCombo.isValidCombo())
        {
            // Although being top-ranked, the combo could not find enough bids.
            // Fail but do nothing.
            return;
        }

        boolean gotWinner = false;
        // Evaluate all bids in our linchpin's highest-ranked Combo.
        while (!gotWinner)
        {
            gotWinner = topCombo.canAllWin(auctioneer, pass, linchpin);

            if (mySegmentStats != null)
                mySegmentStats.ComboEvalCount++;

            if (gotWinner)
            {
                break;
            }
            boolean gotNext = topCombo.next();
            if (!gotNext)
            {
                // No more bids.
                break;
            }

        } // End evaluation loop

        // Record winner if we found one.
        if (gotWinner)
        {
            recordComboWinner(auctioneer, pass, topCombo);
        }

        // Done

    } // handleSegmentedWinnerLinchpinOnly()

    /**
     * SIMPLE VERSION 3 OF ALGORITHM for segment handling.
     * Does NOT guarantee the SegmentSet is filled with winners.
     * A win ONLY marks other overlapping bids in the SegmentSet as losers.
     * This essentially treats SegmentSets as normal bids and does
     * not override the standard auction algorithm.  These bids are marked
     * as a PARTIAL_SEGMENT win type.
     * @param wBid Bid to evaluate.  Has already been judged
     * as able to be a winner itself.  May be null.
     * @param pass Which pass we are executing.
     */
    public void handleSegmentedWinnerSimple(Auctioneer auctioneer,
            AuctionObjectShadow lBid, AuctionPass pass)
    {
        if (lBid == null)
        {
            // A null bid means this is the end of the auction pass.
            // In this algorithm, no further attempts are made.
            return;
        }
        if (lBid.auctionObj.spot.isMirrored())
        {
            // If this is a mirrored spot, use the standard
            // Auctioneer mirror handling, which may fail.
            auctioneer.handleMirroredWinner(lBid, pass, WinType.PARTIAL_SEGMENT);
            if (lBid.auctionState != AuctionStatus.WINNER)
            {
                // The mirrored partner could not win, so
                // this bid did not win. Nothing more to do.
                return;
            }
        }
        else
        {
            // Set this bid to be a winner.
            auctioneer.setAsWinner(lBid, pass, WinType.PARTIAL_SEGMENT);
        }

        // Record winner for this segment set
        recordSingleSegmentSetWinner(findIndxBid(lBid));

    }

    /**
     * COMPLEX VERSION OF ALGORITHM (STILL WORK-IN-PROGRESS)
     * Special processing for a segmented avail bid already judged
     * to be a possible winner.  The bid may be on either a segment
     * parent or one of the child segments.
     * @param wBid Bid to evaluate.  Has already been judged
     * as able to be a winner itself.
     * @param pass Which pass we are executing.
     */
    public void handleSegmentedWinnerComplex(Auctioneer auctioneer,
            AuctionObjectShadow linchpin, AuctionPass pass)
    {

        debugMonitor(DEBUG_START_SEG_HEADER +
                "Pass " + pass + "\n");

        ComboSet combos = new ComboSet(linchpin, pass);
        Combo topCombo = combos.topRanked;
        boolean gotWinner = false;

        // Keep going while we haven't found a winner and
        // we still have valid (fully popluated) combos
        // containing the linchpin.  No need to
        // evaluate canBeWinner on any combos if none of
        // the valid combos contains the linchpin.
        while (!gotWinner && combos.anyContainLinchpin())
        {
            if (topCombo == INVALID_DEFAULT_COMBO)
            {
                // No combos are valid! Nothing to do.
                break;
            }

            // See if all bids in the current top-ranked combo can win.
            boolean ok = topCombo.canAllWin(auctioneer, pass, linchpin);

            if (mySegmentStats != null)
                mySegmentStats.ComboEvalCount++;

            if (ok)
            {
                // Current top ranked combo CAN win.
                // If it has the linchpin, we're done; if not, we just return.
                if (topCombo.containsLinchpin())
                {
                    gotWinner = true;
                }
                break;
            }
            // This top-ranked combo can't win.
            // combos.next() does this:
            //  0. Unprune the loser bid's creatives.
            //  1. Advance the top-ranked to the next bid.
            //  2. Reevaluate the ranks.
            //  3. Possibly change top-ranked Combo.
            boolean gotNext = combos.next();

            topCombo = combos.topRanked;

            if (!gotNext)
            {
                // Our top-ranked combo has run out of bids.
                // XXX: Mark lynchpin with special LOSER code?
                if (mySegmentStats != null)
                    mySegmentStats.ReachedComboEnd++;
                break;
            }

        } // End evaluation loop

        // Record winner if we found one.
        if (gotWinner)
        {
            recordComboWinner(auctioneer, pass, topCombo);
        }
        else if (linchpin == null)
        {
            findPartialWinners(auctioneer, pass);
        }

        // Done.
        if (myDoDebug || theDebugOnTheFly)
        {
            // Put the SegmentSet toString last so we capture the winning bid(s),
            // winDuration & winBudgetTaken.  Only create string if we're debugging.
            String header =
                    "-------------------------------------------------------------------------";
            debugMonitor("\n" + header);
            debugMonitor(this.toString());
            debugMonitor(header);

            debugMonitor(SEGMENT_SET_COMPLETE_DEBUG_ACTION_MSG);
        }

    } // handleSegmentedWinnerComplex()

    /**
     * To be called before we leave processing a SegmentSet
     * or setting winners.
     * If we have changed budget in the course of calculations,
     * flag as a bug.
     */
    private void checkBudget()
    {
        if (myUnrolledBudget != 0)
        {
            try
            {
                theLogger.error("checkBudget(): Failed to unroll budget totals =" +
                        "for segment set: " + this);
            }
            catch (Exception e)
            {
                theLogger.error("checkBudget(): Exception dumping a segment set=",
                        e);
            }

            debugMonitor(" **** FAILED to unroll all of budget. UnrolledBudget=" +
                    myUnrolledBudget);

            myUnrolledBudget = 0;
        }
    }

    /**
     * Add a win conditionally.  Used for conditional
     * process for mirroring, segments, etc.
     * Add to budget and impression numbers for a winning bidder.
     * Add tallies to the appropriate break, daypart, program,
     * bundling & product attribute mappings.
     * @param auctioneer
     * @param aos Bid's budget to add.
     * @param ac
     */
    private void addToTotals(Auctioneer auctioneer,
            AuctionObjectShadow aos, ActionCtxt ac,
            List<AuctionObjectShadow> bidsBudgeted)
    {
        if (aos != null)
        {
            auctioneer.addToConditionalTotals(aos);

            myUnrolledBudget += aos.auctionCost().valueInCents();

            bidsBudgeted.add(aos);
        }
    }

    /**
     * Unroll a conditional win.  Used for conditional
     * process for mirroring, segments, etc.
     * Unroll budget and impression numbers for a bidder.
     * Unroll tallies to the appropriate break, daypart, program,
     * bundling & product attribute mappings.
     * @param auctioneer
     * @param aos Bid's budget to remove.
     * @param ac
     */
    private void unrollTotals(Auctioneer auctioneer,
            AuctionObjectShadow aos, ActionCtxt ac)
    {
        unrollTotals(auctioneer, aos, ac, null);
    }

    /**
     * Remove budget we've temporarily added so far.
     * @param auctioneer
     * @param aos Bid's budget to remove.
     * @param ac
     * @param bidsBudgeted the list of bids that have had budgeting applied.
     */
    private void unrollTotals(Auctioneer auctioneer,
            AuctionObjectShadow aos, ActionCtxt ac,
            List<AuctionObjectShadow> bidsBudgeted)
    {
        if (aos != null)
        {
            auctioneer.unrollConditionalTotals(aos);

            myUnrolledBudget -= aos.auctionCost().valueInCents();

            if (bidsBudgeted != null)
            {
                bidsBudgeted.remove(aos);
            }
        }
    }

    /**
     * Record all bids in a Combo as winners, and
     * mark the remaining bids in the SegmentSet as losers.
     * @param auctioneer
     * @param pass
     * @param combo Combo containing winning bids.
     */
    private void recordComboWinner(Auctioneer auctioneer, AuctionPass pass,
            Combo combo)
    {
        // Victory!  Officially set all the bids as winners.

        // We allow bids having the same segment offset to compete.
        // After finding the winners, we'll choose the appropriate
        // bids (same CB, but appropriate avail) so that the
        // combo fills all segment slots.
        combo.setWinner();

        for (IndxBid wbid : combo.myBids)
        {
            myWinningBids.add(wbid);
            AuctionObjectShadow bid = wbid.bid;

            if (bid.auctionState != AuctionStatus.IN_PLAY)
            {
                String bidstr = null;
                try
                {
                    bidstr = bid.toString();
                }
                catch (Exception e)
                {
                    // In case the bid toString throws.
                }

                theLogger.error("recordComboWinner(): Sold avail that isn't " +
                        "IN_PLAY. Bid: " + bidstr);

                debugMonitor(" ### Invalid selling of avail. Bid:\n" + bidstr;
            }
            auctioneer.setAsWinner(bid, pass, WinType.NORMAL);
            if (wbid.mirrorPartner != null)
            {
                // Assign mirror partner's creative.
                AuctionObjectShadow mPartner = wbid.mirrorPartner.bid;
                mPartner.setSelectedCreativeId(bid.getSelectedCreativeId());
                auctioneer.setAsWinner(mPartner, pass, WinType.MIRR_PARTNER);
            }
        }
        if (mySegmentStats != null)
            mySegmentStats.TotalWinningRankSum += combo.rank();

        // ... and the others in the tree as HAS_WINNER.
        // This covers all bids in bidsTried set back above.
        markAllSegmentNonWinners();

        // If this segment won, its mirror would also have won.
        // Mark its losers appropriately as well.
        if (mirrSegSet() != null)
        {
            mirrSegSet().markAllSegmentNonWinners();
        }
    }

    /**
     * Find partial winners.  Starts from the highest ranked bid
     * in this SegmentSet, and works down the full list until we've
     * either reached the end or filled the Segment with winners.
     * Only consider bids still in play, and only perform search if
     * this inventory owner allows partial wins.
     * @param auctioneer
     * @param pass Auction Pass.
     */
    private void findPartialWinners(Auctioneer auctioneer, AuctionPass pass)
    {
        if (!myAllowPartialWins)
        {
            return;
        }

        // Iterate through list in rank order assigning any winner we can find.
        for (AuctionObjectShadow bid : myAllRankedBids)
        {
            // Only bids still in play
            if (bid.auctionState != AuctionStatus.IN_PLAY)
            {
                debugMonitor(ActionCtxt._SS, findIndxBid(bid),
                        SegmentSetAction.PARTIAL_NOT_IN_PLAY, 0);
                continue;
            }

            if (auctioneer.canBeWinner(bid, pass))
            {
                debugMonitor(ActionCtxt._SS, findIndxBid(bid),
                        SegmentSetAction.WINNER_PARTIAL,
                        segmentSetRankSum());

                // Bid can win.  Set it as winner (this will check mirror).
                handleSegmentedWinnerSimple(auctioneer, bid, pass);

                // Record partial segment stats if we actually got set winner.
                if (bid.auctionState == AuctionStatus.WINNER && mySegmentStats != null)
                {
                    mySegmentStats.TotalWinningRankSum += bid.auctionObj.rank();
                    mySegmentStats.NumPostAuctionWinners++;
                    mySegmentStats.NumPostAuctionPartials++;
                }

                if (foundAllWinners())
                {
                    return;
                }
            }
            // Note we are not resetting the status if it cannot win,
            // since this is the last chance for this bid and we want
            // to record the losing reason.
        }
    }

    /**
     * Get the placement attributes for the creative of a winning bid.
     * @param bid Bid to query.
     * @return List, possibly empty, of boolean attributes.
     */
    private static List<Integer> getWinningBidAttrs(AuctionObjectShadow bid)
    {
        AuctionObject ao = bid.auctionObj;
        int crID = bid.getSelectedCreativeId();
        int orgID = ao.spot.breakView.orgId;
        PlacementAttribute pattr =
                ao.campaignBuy.getPlacementAttrsForOrg(crID, orgID);
        if (pattr == null)
            return new ArrayList<Integer>();

        return pattr.getBooleanAttrs();
    }

    /**
     * Get formatted String of winning bid attributes.
     * @param bid the bid
     * @return a string in the format 'attr#1,attr#2,...'
     */
    public static String getWinningBidCridAttrs(AuctionObjectShadow bid)
    {
        List<Integer> attrList = getWinningBidAttrs(bid);

        int num = 0;
        StringBuilder sb = new StringBuilder();
        for (Integer attr : attrList)
        {
            if (num++ > 0)
                sb.append(",");
            sb.append(attr);
        }
        return sb.toString();
    }

    /**
     * @return String representation of this SegmentSet
     */
    public String toString()
    {
        // SegmentSet RootAvail=227 Dur=60 Day=252 UnrolledBudget=0 allowPartials=true
        StringBuilder sb = new StringBuilder();
        sb.append("SegmentSet RootAvail=" + myRootAvail.id).
        append(" Dur=").append(myRootAvail.duration).
        append(" Day=").append(myRootAvail.gridDayIndex).
        append(" Brk=").append(myRootAvail.breakView.id).
        append(" WinDur=").append(myWinDuration).
        append(" AllowPartials=").append(myAllowPartialWins).
        append(" UnrolledBudget=").append(myUnrolledBudget).
        append("\n");

        Spot mir = (myRootAvail.isMirrored()?
                myRootAvail.mirroredPartner():null);

        if (mir != null)
        {
            sb.append("           MirrorAvl=" + mir.id).
            append(" Dur=").append(mir.duration).
            append(" Day=").append(mir.gridDayIndex).
            append(" Brk=").append(mir.breakView.id);
        }

        sb.append("\nRanked Bid Lists: (Buy|Avail|Rank|Cost)");

        appendBidList(myRankedBidsRoot,   sb, "Root-" +
                myRootAvail.duration + " Offset-0 Bids");

        if (myPairDuration != NO_DURATION)
        {
            appendBidList(myRankedBidsPair_0, sb, "Pair-" +
                    myPairDuration + " Offset-0 Bids");
            appendBidList(myRankedBidsPair_1, sb, "Pair-" +
                    myPairDuration + " Offset-1 Bids");
        }

        if (myQuadDuration != NO_DURATION)
        {
            appendBidList(myRankedBidsQuad_0, sb, "Trio-" +
                    myQuadDuration + " Offset-0 Bids");
            appendBidList(myRankedBidsQuad_1, sb, "Trio-" +
                    myQuadDuration + " Offset-1 Bids");
        }
        return sb.toString();
    }

    /********* CLASS MEMBERS ************/

    private static WinMethod theWinAlgorithm = WinMethod.COMPLEX;

    /** Rank bids descending as we do in the global ranked list */
    /** Comparator to order AuctionShadowObject by descending rank */
    public static final Comparator<IndxBid> RANK_COMPARATOR =
            new Comparator<IndxBid>() {
        public int compare(IndxBid ib1, IndxBid ib2)
        {
            AuctionObjectShadow aos1 = ib1.bid;
            AuctionObjectShadow aos2 = ib2.bid;

            return Auctioneer.RANK_WINNER_DESC_COMPARATOR.compare(aos1, aos2);
        }
    };

    /******* Fake Combo indicating that this set has no usable set of bids. */
    private final Combo INVALID_DEFAULT_COMBO = new Combo(0, ActionCtxt._INVALID, true);

    /** STATS */
    private static long theTstamp = 1;
    private static boolean theDebugOnTheFly = false;

    /************ OBJECT MEMBERS ************/
    /** Top (longest) avail. */
    public final Spot myRootAvail;

    /** duration of the pair of avails that make up the duration of the root. */
    private final int myPairDuration;
    /** duration of the 4 avails that make up the duration of the root. */
    private final int myQuadDuration;

    /** Algorithm */
    public final WinMethod myWinAlgorithm;
    /** Bids to spots in same tree that are of the same parent, rank-ordered. */
    public List<AuctionObjectShadow> myAllRankedBids;
    /** Mirrored partner SegmentSet */
    private SegmentSet myMirroredSegmentSet = null;

    private int myWinDuration = 0;

    List<AuctionObjectShadow> myTmpAllBidList =
            new ArrayList<AuctionObjectShadow>();

    // The bids will be ordered as we add them, avoiding an expensive sort.
    List<AuctionObjectShadow> myTmpRoot = null;
    List<AuctionObjectShadow> myTmpPair_0 = null;
    List<AuctionObjectShadow> myTmpPair_1 = null;
    List<AuctionObjectShadow> myTmpQuad_0 = null;
    List<AuctionObjectShadow> myTmpQuad_1 = null;

    /** Root (60-of-a-60 or 30-of-a-30) second bids presorted */
    public List<IndxBid> myRankedBidsRoot;
    /** Pair (2-30s-of-a-60 or 2-15s-of-a-30) second bids presorted */
    public List<IndxBid> myRankedBidsPair_0;
    public List<IndxBid> myRankedBidsPair_1;
    /** Quad (2-15s of a 60) second bids presorted */
    public List<IndxBid> myRankedBidsQuad_0;
    public List<IndxBid> myRankedBidsQuad_1;

    private List<IndxBid> myWinningBids = new ArrayList<IndxBid>();

    public AuctionObjectShadow myPrevLinchpin = null;

    private final List<DebugAction> myDebugActions;
    private final SegmentStats mySegmentStats;
    private final boolean myDoDebug;
    private boolean myAllowPartialWins = false;
    private long myUnrolledBudget = 0;

    public static final String SEGMENT_SET_COMPLETE_DEBUG_ACTION_MSG =
            "Done with Segment Set.";

    /**************************** INNER CLASSES ******************************/


    /**************************************************
     * IndxBid
     * A single bid with its index into its original list.
     **************************************************/
    public class IndxBid
    {
        /**
         * Constructor
         * @param list Original SegementSet list.
         * @param aos Auction bid.
         * @param i Index of this bid in the original list.
         */
        public IndxBid(List<IndxBid> list, AuctionObjectShadow aos, int i)
        {
            bid = aos;
            index = i;
            origList = list;

            if (mySegmentStats != null)
                mySegmentStats.IndxBidCounter++;
        }

        public SegmentSet segSet()
        {
            return SegmentSet.this;
        }

        /** Original auction bid */
        public final AuctionObjectShadow bid;
        /** Index in SegmentSet duration-silo array */
        public int index;
        /** Duration-silo array */
        public final List<IndxBid> origList;
        /** Mirrored partner IndexBid, if any */
        public IndxBid mirrorPartner = null;

        /**
         * @return String rep of this bid.
         */
        public String toString()
        {
            AuctionObject ao = bid.auctionObj;
            StringBuilder sb = new StringBuilder();
            sb.append("Avl=").append(ao.spot.id).
            append(" Buy=").append(ao.campaignBuy.campaignBuyID).
            append(" Dur=").append(ao.spot.duration).
            append(" Idx=").append(index).
            append(" Off=").append(ao.spot.segmentOffset);

            return sb.toString();
        }

    } // END IndxBid class

    /*************************************
     * Combo
     * A single combination of bids that could
     * win an entire segmented avail.
     *************************************/
    public class Combo
    {
        /**
         * Constructor
         * @param size of bid set for this Combo.
         * @param ctype Type of Combo.
         * @param linchExits boolean indicating if we have a linchpin.
         */
        public Combo(int size, ActionCtxt ctype, boolean linchExists)
        {
            myRequiredSize = size;
            myComboType = ctype;
            myBids = new ArrayList<IndxBid>(size);
            myLinchpinExists = linchExists;

            if (mySegmentStats != null)
            {
                mySegmentStats.ComboCounter++;
            }
        }

        /**
         * Get the parent segment set of this Combo.
         * @return
         */
        public SegmentSet segSet()
        {
            return SegmentSet.this;
        }

        /**
         * Rank of this combo.
         * @return
         */
        public double rank()
        {
            return mySumRanks;
        }

        /**
         *
         * @return the cost of this combo.
         */
        public long cost()
        {
            return mySumCostCents;
        }

        /**
         * @return true if this combo was initialized
         * with the required number of bids.
         */
        public boolean isValidCombo()
        {
            return myIsValid;
        }

        /**
         * Mark that this Combo won.
         */
        public void setWinner()
        {
            // Default does nothing.  Used for debug/tracking subclasses.
            if (myDoDebug || theDebugOnTheFly) // DEBUG-Start
            {
                debugMonitor(myComboType, myComboLinchpin,
                        (myLinchpinExists?SegmentSetAction.WINNER_COMBO:
                            SegmentSetAction.WINNER_COMBO_POST_AUCTION),
                        mySumRanks);

                debugMonitor(dumpComboAttrInfo());

            } // DEBUG-End
        }

        /**
         * Initialize a bid in this Combo's set of bids.
         * If the lBid uses the passed in list, include that as
         * one of the bids, and set it as this Combo's linchpin.
         * @param lBid Linchpin bid.  May be null.
         * @param origList List to draw from.
         * @param stats Stats to update.
         */
        protected void initBid(IndxBid lBid, List<IndxBid> origList)
        {
            // Check whether we include the linchpin in our list first.
            if (lBid != null && lBid.origList == origList)
            {
                myComboLinchpin = lBid;
                addBid(lBid);
                return;
            }

            // Now find the highest-ranked bid from the original
            // list that is still in play and put it in ours.
            for (int i = 0; i < origList.size(); i++)
            {
                IndxBid ibid = origList.get(i);
                if (ibid == myComboLinchpin)
                {
                    // Already in our list.
                    continue;
                }
                AuctionObjectShadow bid = ibid.bid;
                if (bid.auctionState != AuctionStatus.IN_PLAY)
                {
                    // Bid has already been disqualified.  Skip.
                    debugMonitor(myComboType, ibid,
                            SegmentSetAction.NOT_IN_PLAY,
                            mySumRanks);
                    continue;
                }
                addBid(ibid);
                return;
            }

            // Couldn't find a bid to add.
        }

        /**
         * Done initializing this Combo.
         * Must be called by subclass after all constructor initialization
         * has finished.
         * @param stats
         */
        protected void initDone()
        {
            // Set the valid bit based on whether we have
            // the required number of bids.
            myIsValid = (myBids.size() == myRequiredSize);

            // Calculate and set the rank sum & cost of this Combo.
            setRankAndCost();

            // use linchpin if nothing in our combo
            debugMonitor(myComboType,
                    (myBids.size()>0?myBids.get(0):myComboLinchpin),
                    (myIsValid?SegmentSetAction.VALID_COMBO:
                        SegmentSetAction.INVALID_COMBO),
                    mySumRanks);
        }

        /**
         * Tries to find all winners for this Combo.
         * @param auctioneer Auctioneer parent.
         * @param pass Auction Pass we're in.
         * @param linchpin Bid already determined to be winnable.
         * @return true if we found a set of winners.
         */
        private boolean canAllWin(Auctioneer auctioneer, AuctionPass pass,
                AuctionObjectShadow linchpin)
        {
            boolean canWin = true;

            // Initialize state.
            // The call to addToTotals() puts bids in this list.
            myBidsBudgeted.clear();

            // For all the bids in this combo, find possible winners.
            // Note iterator must pick the linchpin bid first!
            for (IndxBid ib : this.myBids)
            {
                AuctionObjectShadow aos = ib.bid;
                if (aos == linchpin)
                {

                    // Navic19808 - Apply to this buy's budget before we check
                    // the mirror.  In mirrorCanWin we check & take budget too.
                    //
                    // Add to budget and impression numbers for this bid.
                    // Add tallies to the appropriate break, daypart, program,
                    // bundling & product attribute mappings.
                    // Add the bid to myBidsBudgeted
                    addToTotals(auctioneer, aos, myComboType, myBidsBudgeted;

                    // We've already checked linchpin for canBeWinner().
                    // Check linchpin mirror can win, if applicable.
                    if (!mirrorCanWin(ib, auctioneer, pass))
                    {
                        // Don't continue.  Mark and set losing bid.
                        myLastLosingBid = ib;

                        // Unroll everything we applied in addToTotals()
                        unrollTotals(auctioneer, aos, myComboType, myBidsBudgeted);

                        canWin = false;
                        break;
                    }
                }
                // If the non-linchpin can't win, then we're done.
                else if (!nonLinchpinCanWin(auctioneer, pass, ib))
                {
                    // Found a loser
                    myLastLosingBid = ib;

                    if (mySegmentStats != null)
                        mySegmentStats.HitLoser++;

                    canWin = false;
                    break;
                }
            }

            // Unroll everything we applied in addToTotals()
            for (AuctionObjectShadow aos : myBidsBudgeted)
            {
                unrollTotals(auctioneer, aos, myComboType);
            }

            // Return our result.
            return canWin;
        }


        /** Determine if this non-linchpin IndxBid & its mirror
         *  can be won by the buy associated with this IndxBid.
         *  @return true if the master & mirror can be won by the buy
         *  associated with this IndxBid.  False otherwise.
         */
        private boolean nonLinchpinCanWin(Auctioneer auctioneer, AuctionPasspass,
                IndxBid ib)
        {
            AuctionObjectShadow aos = ib.bid;

            // Record previous status
            AuctionStatus prevStatus = aos.auctionState;

            ////////////////////////////////////////////////////////
            // 1. check if the bid can win.
            // 2. Take budget, apply adjacency,
            //    apply attributes to this break, and
            //    add the bid to myBidsBudgeted
            // 3. if there is a mirror of this bid,
            //    check if the mirrored sibling can be a winner
            //    and apply budget/impressions for that mirror
            ////////////////////////////////////////////////////////

            // 1. check if this bid's attributes pass (this prunes
            //    the bid's creatives based on the the attributes
            //    for the current winners in this segment set).
            boolean canWin = auctioneer.canBeWinner(aos, pass);

            if (canWin) // 2. the bid can win.
            {
                // 2. Add to budget and impression numbers for this bid.
                // Add tallies to the appropriate break, daypart, program,
                // bundling & product attribute mappings.
                // Add the bid to myBidsBudgeted
                addToTotals(auctioneer, aos, myComboType, myBidsBudgeted);

                if (myDoDebug || theDebugOnTheFly) // DEBUG-Start
                {
                    debugMonitor(myComboType, ib,
                            SegmentSetAction.CAN_BE_WINNER,
                            mySumRanks,
                            ib.bid.getSelectedCreativeId(),
                            null);
                } // DEBUG-End

                // 3. if there is a mirror of this bid,
                //    check if the mirrored sibling can be a winner
                //    and apply budget/impressions for that mirror
                canWin = mirrorCanWin(ib, auctioneer, pass);

                if (!canWin) // 3. mirror can't win
                {
                    // Unroll everything we applied in 2. addToTotals()
                    unrollTotals(auctioneer, aos, myComboType, myBidsBudgeted);
                }

            } // 1. bid can win

            // Record the status before we set it back.
            if (!canWin)
            {
                debugMonitor(myComboType, ib,
                        SegmentSetAction.NOT_CAN_BE_WINNER,
                        mySumRanks,
                        ib.bid.getCreativeIds());
            }

            // Reset the bid's previous status.
            aos.auctionState = prevStatus;

            return canWin;
        }

        /**
         * Check whether a bid's mirrored partner, if any, is winnable.
         * Return true if either ibid is not mirrored, or if the mirrored
         * partner bid exists & can win.  Has side effects of setting
         * reason on original bid if mirror partner could not be found; if he
         * mirror partner bid is winnable, this method will update the budget
         * for the winning mirror partner.
         * @param ibid
         * @param auctioneer
         * @param pass
         * @return
         */
        private boolean mirrorCanWin(IndxBid ibid, Auctioneer auctioneer,
                AuctionPass pass)
        {
            AuctionObjectShadow bid = ibid.bid;
            AuctionSpot as = bid.auctionSpot;
            if (!as.spot.isMirrored())
            {
                // Not a mirrored spot, nothing to check.
                return true;
            }

            // Get the mirrored bid.
            AuctionObjectShadow partnerBid =
                    auctioneer.getInPlayMirroredPartnerBid(bid);
            if (partnerBid == null)
            {
                // No mirrored partner.  Note that the
                // bid will have the failure reason set on it.
                debugMonitor(myComboType, ibid,
                        SegmentSetAction.NULL_MIRROR_BID,
                        mySumRanks);
                return false;
            }

            // Set this ibid's mirror partner (only need to do it once).
            if (ibid.mirrorPartner == null)
            {
                // Must search the partner's SegmentSet!
                SegmentSet mSegSet = segSet().mirrSegSet();
                if (mSegSet != null)
                {
                    ibid.mirrorPartner = mSegSet.findIndxBid(partnerBid);
                    // A null segment partner here may indicate a misconfig.
                    // Check?
                }
                if (ibid.mirrorPartner == null)
                {
                    // MisConfig?  No mirror partner for a mirrored bid.
                    String msg = "mirrorCanWin() Got a null mirrored IndxBid " +
                            "for IndxBid: " + ibid;

                    theLogger.error(msg);

                    debugMonitor(msg);

                    return false;
                }
            }

            // We must use the same creative as was used in the master bid.
            // We need to do this before canBeWinner() is called so that we
            // evaluate product attributes for the appropriate creative.
            partnerBid.setCreativeIds(bid.getSelectedCreativeId());

            AuctionStatus prevState = partnerBid.auctionState;
            if (!auctioneer.canBeWinner(partnerBid, pass))
            {
                debugMonitor(myComboType, ibid.mirrorPartner,
                        SegmentSetAction.MIRROR_NOT_CAN_WIN,
                        mySumRanks,
                        ibid.bid.getCreativeIds());

                // Mirrored partner fails to win for some reason.
                // Erase any error state set by canBeWinner(), and fail.
                partnerBid.auctionState = prevState;

                return false;
            }

            // Add to budget and impression numbers for this partnerBid.
            // Add tallies to the appropriate break, daypart, program,
            // bundling & product attribute mappings.
            // Add the bid to myBidsBudgeted
            addToTotals(auctioneer, partnerBid, ActionCtxt._SS,
                    myBidsBudgeted);

            debugMonitor(myComboType, ibid.mirrorPartner,
                    SegmentSetAction.MIRROR_CAN_WIN,
                    mySumRanks,
                    partnerBid.getSelectedCreativeId(),
                    null);

            return true;
        }

        /**
         * Replace the last losing bid with the next ranked one
         * in its original list.  Has side effect of recalculating
         * sumRank.  If the end is reached, set atEnd flag.
         * @return true if we found another bid, false on any problem.
         */
        public boolean next()
        {
            IndxBid loser = myLastLosingBid;
            if (loser == null)
            {
                //System.out.println("No Losing bid yet!");
                return false;
            }

            // If the loser is the linchpin (due to mirroring for example),
            // then we are done.
            if (loser == myComboLinchpin)
            {
                myIsValid = false;

                debugMonitor(myComboType, loser,
                        SegmentSetAction.LINCHPIN_LOSER,
                        mySumRanks);
                return false;
            }

            int iNext = loser.index + 1;
            if (iNext < loser.origList.size())
            {
                IndxBid newIb = nextInPlay(loser, iNext);
                if (newIb != null)
                {
                    debugMonitor(myComboType, loser,
                            SegmentSetAction.REMOVE_FROM_COMBO,
                            mySumRanks,
                            loser.origList.size(), null);

                    // Replace loser bid with next in orig list.
                    replaceBid(loser, newIb);

                    // Calculate and set the rank sum & cost of this Combo.
                    setRankAndCost();

                    debugMonitor(myComboType, newIb,
                            SegmentSetAction.ADD_TO_COMBO,
                            newIb.bid.auctionCost(), // mySumRanks,
                            iNext, newIb.bid.getCreativeIds());

                    return true;
                }
            }
            // No more left!
            myIsValid = false;

            if (mySegmentStats != null)
                mySegmentStats.ReachedComboEnd++;

            debugMonitor(myComboType, null,
                    SegmentSetAction.OUT_OF_BIDS_COMBO,
                    mySumRanks,
                    loser.origList.size(), null);

            return false;
        }

        /**
         * Does this Combo contain the linchpin?
         * @param tbid
         * @return true if a linchpin was NOT passed into the SegmentSet, or
         * if a linchpin was passed into the SegmentSet and this Combo has
         * that linchpin.
         */
        public boolean containsLinchpin()
        {
            if (!myLinchpinExists)
                return true;

            return (myComboLinchpin != null);
        }

        /**
         * Get the next bid from a list that is in play,
         * and not against a Spot in our bid set, starting at an index.
         * @param loser Losing bid to replace.
         * @param start Starting index.
         * @return next bid still in play or null if can't find one.
         */
        private IndxBid nextInPlay(IndxBid loser, int start)
        {
            List<IndxBid> l = loser.origList;
            for (int i = start; i < l.size(); i++)
            {
                IndxBid ibid = l.get(i);
                AuctionObjectShadow aos = ibid.bid;

                if (aos.auctionState != AuctionStatus.IN_PLAY)
                {
                    // First, make sure it's still in play.
                    continue;
                }
                // This one is eligible!
                return ibid;
            }
            // Done with the list, found nothing.
            return null;
        }

        /**
         * Recalculate the rank and cost of this Combo.
         * Should be called when its list of bids have changed.
         */
        private void setRankAndCost()
        {
            mySumRanks     = calcRankSum();
            mySumCostCents = calcCostSum();
        }

        /**
         * Calculate the sum of costs for this Combo's collection of bids.
         * @return Sum of costs of the bids in this Combo.
         */
        public long calcCostSum()
        {
            return calcCostSum(myBids);
        }

        /**
         * Calculate the sum of costs for the given collection of bids.
         * @return Sum of costs of the given bids.
         */
        public long calcCostSum(Collection<IndxBid> list)
        {
            long sum = 0;
            for (IndxBid ib : list)
            {
                sum += ib.bid.auctionCost().valueInCents();
            }
            return sum;
        }

        /**
         * Calculate the rank sum of a collection of bids.
         * @return Rank sum of the bids in this Combo.
         */
        public double calcRankSum()
        {
            return calcRankSum(myBids);
        }

        /**
         * Calculate the rank sum of a collection of bids.
         * @param Bids
         * @return Rank sum.
         */
        private double calcRankSum(Collection<IndxBid> list)
        {
            double sum = 0f;
            for (IndxBid ib : list)
            {
                sum += ib.bid.auctionObj.rank();
            }
            return sum;
        }

        /**
         * Add a bid to this Combo.
         * @param ibid
         */
        private void addBid(IndxBid ibid)
        {
            insertBid(ibid);

            debugMonitor(myComboType, ibid,
                    SegmentSetAction.ADD_TO_COMBO,
                    ibid.bid.auctionCost(), NO_INDEX,
                    ibid.bid.getCreativeIds());
        }

        /**
         * Replace an existing bid in this Combo with another.
         * @param oldBid Bid to remove.
         * @param newBid Bid to add.
         */
        private void replaceBid(IndxBid oldBid, IndxBid newBid)
        {
            // Remove the old bid
            myBids.remove(oldBid);

            insertBid(newBid);
        }

        private void insertBid(IndxBid newBid)
        {
            // linchpin goes first always.
            if (newBid == myComboLinchpin)
            {
                myBids.add(0, newBid);
                return;
            }

            // Order based on linchpin 1st, then rank & cost.
            // We assume the bids are already ordered by rank & cost.
            IndxBid tmpBid = newBid;
            for (int i=0; i<myBids.size(); i++)
            {
                IndxBid curBid = myBids.get(i);

                // Leave the linchpin alone
                if (curBid == myComboLinchpin)
                    continue;

                float tmpRank = tmpBid.bid.auctionObj.rank();
                float curRank = curBid.bid.auctionObj.rank();
                if (tmpRank > curRank ||
                        (tmpRank == curRank &&
                        tmpBid.bid.auctionCost().valueInCents() >
                        curBid.bid.auctionCost().valueInCents()))
                {
                    myBids.set(i, tmpBid);
                    tmpBid = curBid;
                }
            }
            myBids.add(tmpBid);
        }

        /**
         * @return String rep of Combo.
         */
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(" [");
            if (this.isValidCombo())
            {
                for (IndxBid bid : myBids)
                {
                    if (bid == myComboLinchpin)
                        sb.append("*");
                    sb.append(bid.toString());
                    sb.append(", ");
                }
                sb.append("R: " + this.mySumRanks).
                append(" C: " + this.mySumCostCents);
            }
            else
            {
                sb.append("INVALID");
            }

            sb.append("]");
            return sb.toString();
        }

        /**
         * Dump the creative/attribute info for the winning bids in this
         * Combo as such:
         *
         * Winning BuyId-CrId(Attrs): Buy#2*-Cr#21(0) Buy#2*-Cr#23(0) Buy#7-Cr#19(1)
         * Winning Attrs: 0 0 1
         */
        public String dumpComboAttrInfo()
        {
            StringBuilder sb = new StringBuilder("\tWinning Attrs: ");
            boolean allAllow2Wins = true;
            HashMap<Integer, Boolean> attrAllows2WinsMap =
                    new HashMap<Integer, Boolean>();

            for (IndxBid ibid : myBids)
            {
                AuctionObjectShadow bid = ibid.bid;
                boolean bidAllows = bid.auctionObj.allow2WinsPerBreak;
                String bidAttrs = getWinningBidCridAttrs(bid);

                sb.append(bidAttrs).append(" ");

                allAllow2Wins = (allAllow2Wins && bidAllows);

                List<Integer> attrs = getWinningBidAttrs(bid);
                for (Integer attr : attrs)
                {
                    Boolean allows = attrAllows2WinsMap.get(attr);
                    if (allows == null)
                    {
                        attrAllows2WinsMap.put(attr, Boolean.valueOf(bidAllows);
                    }
                    else
                    {
                        if (allows.booleanValue() && !bidAllows)
                        {
                            // Shouldn't happen
                            sb.append(" BadAttribute ");
                        }
                    }
                }
            }
            sb.append((allAllow2Wins?" All-Allow-2-Wins":""));
            return sb.toString();
        }

        /**************  OBJECT MEMBERS *************/

        /** Required size of this combo */
        public final int myRequiredSize;
        /** Context type for debugging */
        public final ActionCtxt myComboType;
        /** The current set of bids for this Combo */
        public List<IndxBid> myBids;
        /** Do we have a linchpin for this Combo? */
        public boolean myLinchpinExists;
        /** The sum of the ranks for this Combo's current bids */
        private double mySumRanks = Double.NEGATIVE_INFINITY;
        /** The sum of the auctionCost for this Combo's current bids */
        private long mySumCostCents = 0;
        /** If false, we could not find enough bids to fill this combo at init*/
        private boolean myIsValid = false;
        /** Linchpin used in this combo or null */
        private IndxBid myComboLinchpin = null;
        /** Last losing bid */
        public IndxBid myLastLosingBid = null;
        /** List used to track temp budget spending. */
        private List<AuctionObjectShadow> myBidsBudgeted =
                new ArrayList<AuctionObjectShadow>();

    } // END Combo class

    /******************************************************
     * A Combo of a bid on 1 60-second avail in this segment.
     ******************************************************/
    public class ComboRoot
    extends Combo
    {
        public ComboRoot(IndxBid lBid)
        {
            // JDJD: Make ENUM for size of each combo
            super(1, ActionCtxt._ROOT, (lBid!=null));
            initBid(lBid, myRankedBidsRoot);
            initDone();
        }

        @Override
        public void setWinner()
        {
            super.setWinner();

            if (myRootAvail.duration == 60)
            {
                if (mySegmentStats != null)
                    mySegmentStats.Root60ComboWins++;

                if (!myLinchpinExists)
                    if (mySegmentStats != null)
                        mySegmentStats.PostAuctionRoot60Wins++;
            }
            else if (myRootAvail.duration == 30)
            {
                if (mySegmentStats != null)
                    mySegmentStats.Root30ComboWins++;
                if (!myLinchpinExists)
                    if (mySegmentStats != null)
                        mySegmentStats.PostAuctionRoot30Wins++;
            }

        }

    } // END ComboRoot class

    /******************************************************
     * A Combo of a bid on 2 30-second avails in this segment.
     ******************************************************/
    public class ComboPair
    extends Combo
    {
        public ComboPair(IndxBid lBid)
        {
            super(2, ActionCtxt._PAIR, (lBid!=null));

            List<IndxBid> firstBidList  = myRankedBidsPair_0;
            List<IndxBid> secondBidList = myRankedBidsPair_1;

            // We must add the linchpin first, so choose the appropriate
            // list that contains the linchpin (if any).
            if (lBid != null)
            {
                if (lBid.origList == myRankedBidsPair_1)
                {
                    firstBidList  = myRankedBidsPair_1;
                    secondBidList = myRankedBidsPair_0;
                }
            }

            initBid(lBid, firstBidList);

            // Efficiency - if we didn't add the 1st bid,
            // then we'll never be valid.

            // TODO: JDJD: Deal with the case where we don't care if the
            // all bins are filled.
            if (myBids.size() == 1)
            {
                initBid(lBid, secondBidList);
            }

            initDone();
        }

        @Override
        public void setWinner()
        {
            super.setWinner();

            if (myPairDuration == 30)
            {
                if (mySegmentStats != null)
                    mySegmentStats.Pair30ComboWins++;
                if (!myLinchpinExists)
                    if (mySegmentStats != null)
                        mySegmentStats.PostAuctionPair30Wins++;
            }
            else if (myPairDuration == 15)
            {
                if (mySegmentStats != null)
                    mySegmentStats.Pair15ComboWins++;
                if (!myLinchpinExists)
                    if (mySegmentStats != null)
                        mySegmentStats.PostAuctionPair15Wins++;
            }

        }

    } // END ComboPair class


    /******************************************************
     * A Combo of a bid on 1 30-second avail  and 2 15-second
     * avails in this segment.
     ******************************************************/
    public class ComboTrio
    extends Combo
    {
        public ComboTrio(IndxBid lBid)
        {
            super(3, ActionCtxt._TRIO, (lBid!=null));

            List<IndxBid> firstBidList  = myRankedBidsPair_1;
            List<IndxBid> secondBidList = myRankedBidsQuad_0;
            List<IndxBid> thirdBidList  = myRankedBidsQuad_1;

            // We must add the linchpin first, so choose the appropriate
            // list that contains the linchpin (if any).
            if (lBid != null)
            {
                if (lBid.origList == myRankedBidsQuad_0)
                {
                    firstBidList  = myRankedBidsQuad_0;
                    secondBidList = myRankedBidsQuad_1;
                    thirdBidList  = myRankedBidsPair_1;
                }
                else if (lBid.origList == myRankedBidsQuad_1)
                {
                    firstBidList  = myRankedBidsQuad_1;
                    secondBidList = myRankedBidsQuad_0;
                    thirdBidList  = myRankedBidsPair_1;
                }
            }

            initBid(lBid, firstBidList);

            // Efficiency - if we didn't add the 1st bid,
            // then we'll never be valid.
            if (myBids.size() == 1)
            {
                initBid(lBid, secondBidList);

                // Efficiency - if we didn't add the 2nd bid,
                // then we'll never be valid.
                if (myBids.size() == 2)
                {
                    initBid(lBid, thirdBidList);
                }
            }

            initDone();
        }

        @Override
        public void setWinner()
        {
            super.setWinner();

            if (mySegmentStats != null)
                mySegmentStats.QuadComboWins++;

            if (!myLinchpinExists)
                if (mySegmentStats != null)
                    mySegmentStats.PostAuctionQuadWins++;
        }
    }  // END ComboTrio class


    /************************************************************
     * ComboSet
     * A set of Combos that are eligible to win an entire SegmentSet.
     * These are:
     *    1 60 second bid, OR
     *    2 30 second bids, OR
     *    1 30 second bid and 2 15 second bids
     *************************************************************/
    public class ComboSet
    {
        /**
         * Constructor.  Initialize with the top-ranked
         * 60, 30+30, and 15+15+30 Combos.
         * @param lBid Linchpin bid to use.
         */
        public ComboSet(AuctionObjectShadow lBid, AuctionPass pass)
        {
            auctionPass = pass;
            linchpinBid = findIndxBid(lBid);

            // debugMonitor(toStatString());
            debugMonitor("\n" + DEBUG_ACTION_HEADER); // header
            debugMonitor(cContext, linchpinBid,
                    SegmentSetAction.LINCHPIN_SET, 0,
                    (linchpinBid == null ? 0 :
                        linchpinBid.bid.getSelectedCreativeId()),
                    null);

            comboRoot = new ComboRoot(linchpinBid);
            comboPair = new ComboPair(linchpinBid);
            comboTrio = new ComboTrio(linchpinBid);
            combos = new Combo[] {
                    comboRoot,
                    comboPair,
                    comboTrio
            };

            if (mySegmentStats != null)
                mySegmentStats.ComboSetCounter++;

            chooseTopCombo();
        }

        /**
         * Determine if any of the valid combos contains the linchpin.
         * @return true if any of the valid combos contains the linchpin,
         * false otherwise.
         */
        public boolean anyContainLinchpin()
        {
            for (Combo c : combos)
            {
                if (c.isValidCombo() && c.containsLinchpin())
                {
                    return true;
                }
            }

            debugMonitor(cContext, null,
                    SegmentSetAction.NO_COMBOS_WITH_LINCHPIN, 0);

            return false;
        }

        /**
         * Have the top-ranked Combo move to its next
         * bid, and recalculate ranks and (as a side effect)
         * the top ranked Combo.
         * @return true if we found another bid
         */
        public boolean next()
        {
            // Always re-calculate the top combo even if the
            // current topRanked runs out of bids.
            topRanked.next();

            chooseTopCombo();

            return (topRanked != INVALID_DEFAULT_COMBO);
        }

        /**
         * Assign 'topRanked' to the Combo with the
         * highest sum of ranks.
         */
        private void chooseTopCombo()
        {
            // Set default in case no Combo is valid.
            topRanked = INVALID_DEFAULT_COMBO;
            for (Combo c : combos)
            {
                if (!c.isValidCombo())
                {
                    // Not valid Combo.
                    continue;
                }
                if ((c.rank() > topRanked.rank()) ||
                        (c.rank() == topRanked.rank() &&
                        c.cost() > topRanked.cost()))
                {
                    topRanked = c;
                }
            }
            if (mySegmentStats != null)
                mySegmentStats.RankEvalCount++;

            if (myDoDebug || theDebugOnTheFly) // DEBUG-Start
            {
                if (topRanked == INVALID_DEFAULT_COMBO)
                {
                    debugMonitor(cContext, null,
                            SegmentSetAction.NO_MORE_COMBOS, 0);
                }
                else
                {
                    debugMonitor(topRanked.myComboType, topRanked.myBids.get(0),
                            SegmentSetAction.TOP_RANKED_COMBO,
                            topRanked.calcRankSum());
                }
            } // DEBUG-End
        }

        /********** OBJECT MEMBERS ********/
        public final AuctionPass auctionPass;
        public Combo topRanked;
        public final IndxBid linchpinBid;
        public final ActionCtxt cContext = ActionCtxt._CS;

        private final Combo comboRoot;
        private final Combo comboPair;
        private final Combo comboTrio;
        private final Combo[] combos;

    } // END ComboSet class

    /*********************** SEGMENT SET DEBUG SECTION ***********************/

    /**
     * Set whether we will turn on/off dumping out the segment set
     * debug information on-the-fly.  If true, we'll print the
     * debug info as we go, otherwise we'll store the debug info
     * and dump it out via a call to dumpDebugActions().
     * @param val the value
     */
    public static void setDebugOnTheFly(boolean val)
    {
        theDebugOnTheFly = val;
    }

    /**
     *  Dump the segment set debug information as a string.
     *  @param debugActions the actions to print out.
     *  @return the segement set debug information as a string.
     */
    public static String dumpDebugActions(List<DebugAction> debugActions)
    {
        StringBuilder sb = new StringBuilder();

        for (DebugAction da : debugActions)
        {
            sb.append(da).append("\n");
        }
        return sb.toString();
    }

    /**
     *  Dump the segment set debug information to the given
     *  print writer.
     *  @param debugActions the actions to print out.
     *  @param out the print writer to which we're writing.
     */
    public static void dumpDebugActions(List<DebugAction> debugActions,
            PrintWriter out)
    {
        for (DebugAction da : debugActions)
        {
            out.println(da.toString());
        }
    }

    private static final String DEBUG_ACTION_HEADER =
            "TStamp   RootAv  Avail  BuyId Dur Off CreatIds              Rank  RankSum/Cost CrCb  Action                State                 EndState (UnprunedCrids)\n" +
                    "------------------------------------------------------------------------------------------------------------------------------------------------";

    private static final String DEBUG_START_SEG_HEADER =
            "\n=======================================================================\n";

    /**
     * Store the SegmentSet actions for debugging.  Or dump them out to sysout
     * if theDebugOnTheFly is true.
     */
    private void setDebugAction(DebugAction da)
    {
        if (theDebugOnTheFly)
        {
            System.out.println(da.toString());
        }

        if (myDebugActions != null)
        {
            myDebugActions.add(da);
        }
    }

    /**
     * Return a string in this form:
     * AvailId=1234 FileId=222 Break#=1 Daypart#=15 Channel#=10 Org#=100
     * Zone#=1 TotalViews(DIG+ANAL)=230 TotalViews(DIGITAL)=143
     * GridDay(YYYYDDD)=2010135 MirrorType: NONE SegmentRoot: 1234
     *  #60s=10 High=0129.20 Low=0000.34 Avg=0063.45
     *  #30s=20 High=0109.20 Low=0000.34 Avg=0043.45
     *  #15s=20 High=0080.20 Low=0000.34 Avg=0018.45
     */
    public String toStatString()
    {
        StringBuilder sb = new StringBuilder();
        if (myRankedBidsRoot != null && myRankedBidsRoot.size() > 0)
        {
            sb.append(myRankedBidsRoot.get(0).bid.auctionObj.spot.miniString());
        }
        else
        {
            sb.append("RootAvailId=").append(myRootAvail.id);
        }
        sb.append("\n ").
        append(stringForRankedBids(myRankedBidsRoot, "#" +
                myRootAvail.duration + "s")).
        append("\n ").
        append(stringForRankedBids(myRankedBidsPair_0, "#" + myPairDuration +
                "s-Offset0")).
        append("\n ").
        append(stringForRankedBids(myRankedBidsPair_1, "#" + myPairDuration +
                "s-Offset1")).
        append("\n ").
        append(stringForRankedBids(myRankedBidsQuad_0, "#" + myQuadDuration +
                "s-Offset0")).
        append("\n ").
        append(stringForRankedBids(myRankedBidsQuad_1, "#" + myQuadDuration +
                "s-Offset1")).
        append("\n ");
        return sb.toString();
    }

    private String stringForRankedBids(List<IndxBid> rankedBids,
            String tag)
    {
        StringBuilder sb = new StringBuilder();
        float hi = 0;
        float low = 0;
        float total = 0;
        if (rankedBids == null)
        {
            return tag + "=NoBids";
        }

        int size = rankedBids.size();

        if (size > 0)
        {
            hi = rankedBids.get(0).bid.auctionObj.rank();
            low = rankedBids.get(size-1).bid.auctionObj.rank();

            for (IndxBid iBid : rankedBids)
            {
                total += iBid.bid.auctionObj.rank();
            }
        }
        else
        {
            hi = 0;
            low = 0;
            total = 0;
        }

        sb.append(tag).append("=").append(size).
        append(" High=").append(String.format("%10.2f", hi)).
        append(" Low=").append(String.format("%10.2f", low));
        if (size > 0)
            sb.append(" Avg=").
            append(String.format("%10.2f", (total/(float)size)));

        return sb.toString();
    }

    /**
     * Context enumerations for debugging events.
     */
    enum ActionCtxt {
        _SS, /** SegmentSet */
        _CS, /** ComboSet */
        _QUAD, /** ComboQuad */
        _TRIO, /** ComboTrio */
        _PAIR, /** ComboPair */
        _ROOT, /** ComboRoot */
        _INVALID /** Invalid Combo */
    };

    /**
     * Enumerations for Actions that occur within the Segment Set.
     */
    enum SegmentSetAction
    {
        LINCHPIN_SET,
        ADD_TO_COMBO,
        CAN_BE_WINNER,
        NOT_CAN_BE_WINNER,
        MIRROR_CAN_WIN,
        MIRROR_NOT_CAN_WIN,
        NULL_MIRROR_BID,
        REMOVE_FROM_COMBO,
        VALID_COMBO,
        INVALID_COMBO,
        OUT_OF_BIDS_COMBO,
        WINNER_COMBO,
        WINNER_COMBO_POST_AUCTION,
        WINNER_PARTIAL,
        TOP_RANKED_COMBO,
        NOT_IN_PLAY,
        PARTIAL_NOT_IN_PLAY,
        NO_MORE_COMBOS,
        NO_COMBOS_WITH_LINCHPIN,
        LINCHPIN_LOSER
    }

    /**
     *  Add a msg to the list of debug actions.
     *  @param msg the msg to add to the given actions list.
     *  @param actions the list of debug actions to which we're
     *  adding this msg.
     */
    public void addDebugAction(String msg,
            List<SegmentSet.DebugAction> actions)
    {
        if (actions != null)
        {
            actions.add(new DebugAction(msg));
        }
    }

    private static final int NO_INDEX = -1;
    private static final int NO_DURATION = -1;

    /** Debug with string arg */
    private boolean debugMonitor(String msg)
    {
        if (myDoDebug || theDebugOnTheFly)
        {
            setDebugAction(new DebugAction(msg));
        }
        return true;
    }

    /** Debug with cost and index args */
    private boolean debugMonitor(ActionCtxt ctxt, IndxBid ib,
            SegmentSetAction ssa, Money cost, int index,
            Collection<Integer> crids)
    {
        if (myDoDebug || theDebugOnTheFly)
        {
            DebugAction da = new DebugAction(ctxt, ib, ssa, mySegmentStats,
                    cost.toString(), index, crids);
            setDebugAction(da);
        }
        return true;
    }

    /** Debug with rank argument, no attrs, no index */
    private boolean debugMonitor(ActionCtxt ctxt, IndxBid ib,
            SegmentSetAction ssa,
            double rankSum)
    {
        return debugMonitor(ctxt, ib, ssa, rankSum, NO_INDEX, null);
    }

    /** Debug with rank and attr args, no index */
    private boolean debugMonitor(ActionCtxt ctxt, IndxBid ib,
            SegmentSetAction ssa,
            double rankSum,
            Collection<Integer> crids)
    {
        return debugMonitor(ctxt, ib, ssa, rankSum, NO_INDEX, crids);
    }

    /** Debug with rank, attr, and index args */
    private boolean debugMonitor(ActionCtxt ctxt, IndxBid ib,
            SegmentSetAction ssa,
            double rankSum,
            int index,
            Collection<Integer> crids)
    {
        if (myDoDebug || theDebugOnTheFly)
        {
            DebugAction da = new DebugAction(ctxt, ib, ssa, mySegmentStats,
                    rankFmt(rankSum), index, crids);
            setDebugAction(da);
        }
        return true;
    }

    private String rankFmt(double r)
    {
        return String.format("%14.2f", r);

    }

    /**
     *  Class used for storing a segment set action. Allows us
     *  to track what happened and when in the Segment sets
     *  during an auction.
     */
    class DebugAction
    {
        /**
         * Constructor with PlacementAttributes.
         * @param ctxt
         * @param ib
         * @param ssa
         * @param stats
         * @param rankSumCost
         * @param crids
         */
        public DebugAction(ActionCtxt ctxt, IndxBid ib,
                SegmentSetAction ssa,
                SegmentStats stats,
                String rankSumCost,
                Collection<Integer> crids)
        {
            this(ctxt, ib, ssa, stats, rankSumCost, NO_INDEX, crids);
        }

        /**
         * Standard constructor.
         * @param ctxt
         * @param ib
         * @param ssa
         * @param stats
         * @param rankSumCost
         * @param index
         */
        public DebugAction(ActionCtxt ctxt, IndxBid ib, SegmentSetAction ssa,
                SegmentStats stats, String rankSumCost, int index,
                Collection<Integer> crids)
        {
            myIb = ib;
            mySsa = ssa;
            myTstamp = (stats == null ? theTstamp++ : stats.Tstamp++);
            myRankSumCost = rankSumCost;
            myIndex = index;
            myContext = ctxt;
            myState = (myIb == null ? null : myIb.bid.auctionState);
            myDebugStr = null;

            if (crids != null)
            {
                StringBuilder sb = new StringBuilder();
                sb.append("(");
                for (Integer crid : crids)
                {
                    sb.append(crid).append(",");
                }
                sb.append(")");
                myUnprunedCrids = sb.toString();
            }
        }

        /**
         * Constructor with String.
         * @param debugStr
         */
        public DebugAction(String debugStr)
        {
            myDebugStr = debugStr;
            myContext = null;
        }

        /**
         *  Print out the following columns of a DebugAction object:
         *
         *  TStamp RootAv Avail BuyId Dur Off CreatIds Rank RankSum Indx
         *  Action State EndState
         *
         * Examples (all fields are returned on a single line in
         *           the "real" output).
         *
         * 00023882 0000000 0000000 00000 00  0
         * 0.00        0.00 -001 LINCHPIN_SET_CS
         * NONE       NONE
         *
         * 00023883 0000591 0000591 00018 30  0  19,18,20,
         * 3816288.25  3816288.25 0000 ADD_TO_COMBO_ROOT
         * IN_PLAY    WINNER
         */
        public String toString()
        {
            if (myDebugStr != null)
            {
                StringBuilder sb = new StringBuilder();
                if (myDebugStr.
                        equals(SegmentSet.SEGMENT_SET_COMPLETE_DEBUG_ACTION_MSG))
                {
                    sb.append("\n CrIds->Attrs: ");

                    // This is the last debug action for this evaluation of
                    // this segment set.  Dump out the creative/attributes
                    // for all creatives used in this segment set.
                    int strdelta = 0;
                    for (Integer crID : theCreativeAttMap.keySet())
                    {
                        String attrs = theCreativeAttMap.get(crID);

                        // (8->2,3,4) (9->3,5,8)
                        sb.append("(").
                        append(crID).append("->").
                        append(attrs).append(") ");
                        if ((sb.length() - strdelta) > MAX_LINE_LEN)
                        {
                            strdelta = sb.length();
                            sb.append("\n CrIds->Attrs: ");
                        }
                    }
                    sb.append("\n\n");

                    // Now dump out boolean attribute names.
                    strdelta = sb.length();
                    for (Integer indx : theAttrNameMap.keySet())
                    {
                        // Attr #3->'Likes TV'
                        String nm = theAttrNameMap.get(indx);
                        sb.append(" Attr #").
                        append(indx).
                        append("->\'").
                        append(nm).
                        append("\'  ");
                        if ((sb.length() - strdelta) > MAX_LINE_LEN)
                        {
                            strdelta = sb.length();
                            sb.append("\n ");
                        }
                    }
                    sb.append("\n");
                }
                sb.append(myDebugStr);
                return sb.toString();
            }

            int rootId = 0;
            int availId = 0;
            int buyId = 0;
            int dur = 0;
            double rank = 0;
            String state = (myState==null?"NONE":myState.toString());
            String endState = "NONE";
            int index = myIndex;
            int offset = 0;
            String actionStr = mySsa.toString() + myContext.name();
            String allowStr = " ";
            String crids = "";

            if (myIb != null)
            {
                AuctionObject ao = myIb.bid.auctionObj;
                rootId  = ao.spot.segmentRootID;
                availId = ao.spot.id;
                buyId   = ao.campaignBuy.campaignBuyID;
                dur     = ao.spot.duration;
                offset  = ao.spot.segmentOffset;
                rank    = ao.rank();
                endState = myIb.bid.auctionState.toString();
                index   = (myIndex == NO_INDEX ? myIb.index : myIndex);
                allowStr = (ao.allow2WinsPerBreak?"*":" ");

                // Get the PlacementAttributes from all of this bid's creatives.
                CampaignBuyAuctionInfo cb = ao.campaignBuy;
                int orgID = ao.spot.breakView.orgId;
                StringBuilder sbCrids = new StringBuilder();
                for (Integer crId : ao.creativeIds)
                {
                    sbCrids.append(crId).append(",");
                    StringBuilder sb = null;
                    if (theCreativeAttMap.get(crId) == null)
                    {
                        sb = new StringBuilder();
                        PlacementAttribute pa =
                                cb.getPlacementAttrsForOrg(crId, orgID);
                        if (pa != null)
                        {
                            List<Integer> attrList = pa.getBooleanAttrs();
                            int num = 0;
                            for (int attrId : attrList)
                            {
                                if (num++!=0)
                                    sb.append(",");
                                sb.append(attrId);
                                // record name
                                String name = cb.getAttrNameByIndex(attrId);
                                if (name != null)
                                    theAttrNameMap.put(attrId, name);
                            }
                        }
                        theCreativeAttMap.put(crId, sb.toString());
                    }
                }

                crids = sbCrids.toString();
            }

            // Fields must align with DEBUG_ACTION_HEADER, above!
            return String.format("%08d %07d %07d %05d %02d %2d%s %-16s %14.2f %14s %04d %-22s %-21s %s %s",
                    myTstamp, rootId, availId, buyId,
                    dur, offset, allowStr, crids,
                    rank, myRankSumCost, index,
                    actionStr, state, endState,
                    (myUnprunedCrids==null?"":myUnprunedCrids);
        }

        public SegmentSetAction ssa()
        {
            return mySsa;
        }

        private final ActionCtxt myContext;
        private IndxBid myIb;
        private SegmentSetAction mySsa;
        private long myTstamp;
        private String myRankSumCost;
        private int myIndex;
        private AuctionStatus myState;
        private final String myDebugStr;
        private String myUnprunedCrids = null;
    } // END DebugAction class

    /** Attributes we have accumulated per creative ID */
    public static Map<Integer,String> theCreativeAttMap =
            new TreeMap<Integer,String>();
    public static Map<Integer,String> theAttrNameMap =
            new TreeMap<Integer,String>();

    private static int MAX_LINE_LEN = 80;

    private static Logger theLogger = Logger.getLogger(SegmentSet.class);

} // END SegmentSet class
