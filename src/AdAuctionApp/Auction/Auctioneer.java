/**
 * Part of a source code package originally written for the AdAuctionApp project.
 * Intended for use as a programming work sample file only.  Not for distribution.
 **/
package AdAuctionApp.Auction;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;

import util.TimeUtils;
import AdAuctionApp.AdBuyGrid.AuctionClient;
import AdAuctionApp.AdBuyGrid.GridAuctionObject;
import AdAuctionApp.AdBuyGrid.SpotShadow;
import AdAuctionApp.AdBuyGrid.Auction.AuctionViewToggles;
import AdAuctionApp.AdvertisingAccount.AdvertisingAccountConstants.MediaBuyType;
import AdAuctionApp.Auction.AuctionConstants.PricingType;
import AdAuctionApp.Auction.AuctionConstants.WinType;
import AdAuctionApp.Auction.Central.SystemAuctionClient;
import AdAuctionApp.Auction.SOAPMessage.BidSnapshot;
import AdAuctionApp.Cache.Central.AuctionObject;
import AdAuctionApp.Cache.Central.BreakView;
import AdAuctionApp.Cache.Central.ChannelBundlingInfo;
import AdAuctionApp.Cache.Central.CampaignBuyAuctionInfo;
import AdAuctionApp.Cache.Central.DemographicCache;
import AdAuctionApp.Cache.Central.GridUtils;
import AdAuctionApp.Cache.Central.InventoryTracker;
import AdAuctionApp.Cache.Central.PreassignedWinner;
import AdAuctionApp.Cache.Central.PreassignedWinner.AssignStatus;
import AdAuctionApp.Cache.Central.Spot;
import AdAuctionApp.Core.AdAuctionAppConstants;
import AdAuctionApp.Core.AuctionTally;
import AdAuctionApp.Core.DateRange;
import AdAuctionApp.Core.DayOfWeek;
import AdAuctionApp.Core.Money;
import AdAuctionApp.Core.SystemAlert;

/**
 * This class contains auction data for a grid and implements the auctioning
 * algorithm.
 * 
 * @author sgilbane
 */
public class Auctioneer implements AuctionResults
{
   /**
    * Type of auction being run.
    */
   public enum AuctionType
   {
      /** Standalone, single auction. */
      SINGLE,
      /** Initial auction of double. Resets stats. */
      GREENFIELD,
      /** Follow-on auction of double auction. Saves previous stats. */
      BASELINE
   }

   /**
    * Constructor
    * 
    * @param pool AuctionPoolProvider providing spots and system policies.
    * @param grid AuctionClient running this auction.
    * @param isProposal If true, this will run auctions suitable for proposals.
    */
   public Auctioneer(AuctionPoolProvider pool, AuctionClient grid,
      boolean isProposal)
   {
      myClient = grid;
      myAuctionPool = pool;
      AuctionSettings settings = pool.getAuctionSettings();
      myCampaignBuyId = grid.adBuyId();
      myBudget = new AuctionBudget(myCampaignBuyId);
      myAuctionSettings = settings;
      myAuctionWinMargin = settings.minAuctionWinMargin();
      myEfficiencyThrPct = settings.efficiencyThresholdPct();
      myCpmThreshold = settings.cpmThresholdPct();
      myRateCardDiscountsByOrg = settings.rateCardDiscountsByOrg();
      myRemainImprLowerThreshPct = settings.remainingImprLowerThresholdPct();
      myForcePreviousWinCbCreative = settings.forcePreviousWinCbCreative();
      myPassList = pool.getAuctionPassList();
      myPreassignedWinners.addAll(pool.getPreassignedWinners());
      mySegments = new ArrayList<SegmentSet>();
      if (myCampaignBuyAuctionInfo == null)
      {
         myCampaignBuyAuctionInfo = NO_CAMPAIGN_BUY;
      }
      myStats = new AuctionStats(0, grid);
      myCurAuctionType = AuctionType.SINGLE;
   }

   /**
    * Identifying string for this auction. ID changes each time an auction is
    * run.
    * 
    * @return ID String.
    */
   public String id()
   {
      return myStats.name();
   }

   /**
    * @return The AuctionPoolProvider for this auction.
    */
   public AuctionPoolProvider auctionPool()
   {
      return myAuctionPool;
   }

   /**
    * @return The Campaign Buy ID of the Auction Client, or -1 if none.
    */
   public int campaignBuyId()
   {
      return myCampaignBuyId;
   }

   /**
    * Get the total number of spots being auctioned.
    * 
    * @return Total spot count.
    */
   public int auctionSpotCount()
   {
      return myAuctionSpots.size();
   }

   /**
    * Get total number of competing Ad Buys in this auction.
    * 
    * @return Count of participating Ad Buys.
    */
   public int campaignBuyCompetitorCount()
   {
      return myCompetitorsByBuyId.size();
   }

   /**
    * @return true if this is a real auction, false if this is a simulated
    *         auction. Real auctions use the special SYSTEM_ADBUY_ID value as
    *         the client buy ID.
    */
   public boolean isRealAuction()
   {
      return (myCampaignBuyId == SystemAuctionClient.SYSTEM_ADBUY_ID);
   }

   /************************ SEGMENT DEBUG SECTION **********************/

   /**
    * Set all debugging flags on/off in this Auctioneer. Note: setting this to
    * true WILL have performance implications.
    * 
    * @param doDebug If true, set all debugging features to be on.
    */
   public void setDebug(boolean doDebug)
   {
      setSnapshotPasses(doDebug);
      setSegmentStats(doDebug);
      setSegmentDebug(doDebug);
   }

   /**
    * Set whether or not to snapshot the bid state at the end of each pass.
    * Useful for debugging. If this flag is false, dumping any bid results will
    * only show the final state of all bids, not those between passes.
    * 
    * @param doSnaps If true, snapshot each auction pass.
    * @see #dumpSnapshots(PrintWriter)
    */
   public void setSnapshotPasses(boolean doSnaps)
   {
      mySnapshotEachPass = doSnaps;
   }

   /**
    * Set whether or not to debug the Segmentation of avails. This will turn
    * on/off the storing/dumping of SegmentSet actions.
    * 
    * @param doSegmentDebug if true, debug the Segmentation of avails (this will
    *        have performance/memory implications).
    */
   public void setSegmentDebug(boolean doSegmentDebug)
   {
      myDoSegmentDebug = doSegmentDebug;
   }

   /**
    * Set whether or not to debug the Segmentation of avails. This will turn
    * on/off the storing/dumping of SegmentSet actions.
    * 
    * @param doSegmentDebug if true, debug the Segmentation of avails.
    */
   public void setSegmentStats(boolean doSegmentStats)
   {
      if (doSegmentStats)
      {
         if (mySegmentStats == null)
            mySegmentStats = new SegmentStats();
      }
      else
      {
         mySegmentStats = null;
      }
   }

   /**
    * Get a copy of the list of SegmentSet built during the last auction.
    * Synchronized to prevent doing this in the middle of an auction.
    * 
    * @return List, possibly empty (never null), of SegmentSets.
    */
   public synchronized List<SegmentSet> segmentSets()
   {
      List<SegmentSet> rtnList = new ArrayList<SegmentSet>();
      rtnList.addAll(mySegments);
      return rtnList;
   }

   /************************** AUCTION RESULTS **************************/

   /**
    * Get client's CPM (Cost per thousand viewers) of last auction.
    * 
    * @return CPM of last auction.
    */
   public float lastAuctionCpm()
   {
      return myLastAuctionCpm;
   }

   /**
    * Get cost for client of last auction.
    * 
    * @return Cost in dollars of last auction.
    */
   public Money lastAuctionCost()
   {
      return myLastAuctionCost;
   }

   /**
    * Get total views won for client in last auction.
    * 
    * @return Total views won in last auction.
    */
   public long lastAuctionViewers()
   {
      return myLastAuctionImpr;
   }

   /**
    * Get target views won for client in last auction.
    * 
    * @return Target views won in last auction.
    */
   public int lastAuctionEfficiency()
   {
      return myLastAuctionEff;
   }

   /**
    * Get the winning bids from the last auction.
    * 
    * @return List of winning AuctionObjectShadow objects from last auction.
    */
   public List<AuctionObjectShadow> lastAuctionWinners()
   {
      return myLastWinners;
   }

   /**
    * Get all spots which had no winning bid from the last auction that
    * previously had had a recorded winner.
    * 
    * @return List of AuctionSpot objects from last auction that previously had
    *         had a winner.
    */
   public List<AuctionSpot> lastAuctionUnexchangedSpots()
   {
      List<AuctionSpot> rtnList = new LinkedList<AuctionSpot>();
      for (AuctionSpot aSpot : myAuctionSpots.values())
      {
         if (aSpot.winner == null && aSpot.spot.currentWinnerBuyId > 0)
         {
            rtnList.add(aSpot);
         }
      }
      return rtnList;
   }

   /**
    * @return Auction spending info for our client buy. May be null.
    */
   public CampaignBuyAuctionTally lastAuctionCampaignBuySpending()
   {
      return myLastAuctionClientSpending;
   }

   /**
    * Get the array of all auction object shadows in last auction. Never null.
    * 
    * @return AuctionObjectShadow array.
    */
   public AuctionObjectShadow[] lastAuctionBids()
   {
      return myLastAuctionBids;
   }

   /**
    * Get the array of all auction objects. Never null. XXX: Change allBidders()
    * name in AuctionResults IF to lastAuctionBids.
    * 
    * @return AuctionObjectShadow array. XXX: Change allBidders() name in
    *         AuctionResults IF to lastAuctionBids.
    */
   public AuctionObjectShadow[] allBidders()
   {
      return lastAuctionBids();
   }

   /**
    * Get the size of the array of bidders on behalf of this Grid.
    * 
    * @return Length of GridAuctionObject array.
    */
   public int gridBiddersCount()
   {
      return myClientAuctionObjects.length;
   }

   /**
    * Set the results from the last auction. Called at the end of the auction.
    * If a new auction starts, these values will remain.
    */
   private void setAuctionResults()
   {
      CampaignBuyAuctionTally buySpend = myBudget.clientTally();
      myLastAuctionCost =
         (buySpend == null ? Money.ZERO : buySpend.currentSpending());
      myLastAuctionImpr =
         (buySpend == null ? 0 : buySpend.currentImpressions());
      myLastAuctionCpm =
         AuctionUtils.calculateCPM(myLastAuctionImpr, myLastAuctionCost);
      myLastAuctionEff = myStats.adbuyEfficency();
      myLastAuctionClientSpending = buySpend;
      myLastAuctionBids = myAllAuctionObjects;
      myAllAuctionObjects = new AuctionObjectShadow[0];
   }

   /**
    * Return a Map that contains the spending by DayOfWeek as a result of this
    * auction for a given campaign buy in a given campaign.
    * 
    * @param campaignID Campaign ID
    * @param buyID Campaign Buy ID
    * @return Map of spending for a campaign buy for each DayOfWeek. If the buy
    *         information is not found, all values will be 0.
    */
   public Map<DayOfWeek, AuctionTally> getDayOfWeekTally(
      int campaignID,
      int buyID)
   {
      return myBudget.getDayOfWeekTally(campaignID, buyID);
   }

   /**
    * Get the CampaignAuctionSpending info for a Campaign.
    * 
    * @param campaignID
    * @return CampaignAuctionSpending or null if not found.
    */
   public CampaignAuctionTally getCampaignTally(int campaignID)
   {
      return myBudget.getCampaignTallyResultsMap().get(campaignID);
   }

   /**
    * Get the CampaignBuyAuctionSpending info for a CampaignBuyAuctionInfo.
    * 
    * @param buy CampaignBuyAuctionInfo definition
    * @return CampaignBuyAuctionSpending or null if not found.
    */
   public CampaignBuyAuctionTally getCampaignBuyTally(CampaignBuyAuctionInfo cb)
   {
      CampaignBuyAuctionTally buySpend = null;
      CampaignAuctionTally cSpend = getCampaignTally(cb.campaignID);
      if (cSpend != null)
      {
         buySpend = cSpend.talliesByCampaignBuyID.get(cb.campaignBuyID);
      }
      return buySpend;
   }

   /**
    * Set the date range for this Auction from the current values of the client.
    * May cause recreation of data.
    */
   private void setDateRange()
   {
      // XXX: For now, we recreate all the data each auction run.
      // We used to do nothing if the client date range
      // had not changed since the previous auction, but the client
      // auction info also needs to be refreshed each time, and
      // then reattached to all GridAuctionObjectShadows.
      // It's easier to rebuild everything from scratch.
      //
      // A fix would be to deep-copy changes in the Ad Buy into
      // a fixed client CampaignBuyAuctionInfo object.
      createAuctioningData(myClient.auctionDateRange());
   }

   /********************************************************
    * INITIALIZATION SECTION
    ********************************************************/

   /**
    * Re-fetch auction data from the client, which may have changed since the
    * last auction.
    */
   private void resetClientAuctionInfo()
   {
      myCampaignBuyAuctionInfo = myClient.auctionInfo();
      if (myCampaignBuyAuctionInfo == null)
      {
         myCampaignBuyAuctionInfo = NO_CAMPAIGN_BUY;
      }
      myCurToggles = myClient.auctionConstraints();
      myStats.setToggles(myCurToggles);
   }

   /**
    * Recalculate various values, based on current AuctionToggles, for all our
    * Ad Buy AuctionObjects.
    * 
    * @param AuctionViewToggles
    */
   private void recalculateGridValues(AuctionViewToggles toggles)
   {
      for (GridAuctionObject ao : myClientAuctionObjects)
      {
         ao.recalculateValues(toggles);
      }
   }

   /**
    * create auctioning data from grid and cache given the date range.
    * 
    * @param dr Date Range over which we are auctioning
    */
   private void createAuctioningData(DateRange dr)
   {
      resetClientAuctionInfo();

      // Create auction objects
      createAuctionObjects(dr.startDate(), dr.endDate());

      // second, create auction spending data
      createAuctionSpendingData();

      myStats.timestamp("CREATED-AUCTION-DATA");
   }

   /**
    * Create AuctionObjectShadows and AuctionSpots from the AuctionPool and
    * AuctionClient data.
    * 
    * @param start Start date.
    * @param end End date.
    */
   private void createAuctionObjects(Calendar start, Calendar end)
   {
      // myClient is either a MasterGrid or SpotScheduleCache.
      // A MasterGrid will return a Map of SpotId to SpotShadow
      // and the SpotShadow will contain the subset of creativeIds
      // that have passed creative rotation, creative language & placement
      // validation.
      Map<Integer, SpotShadow> ourGridSpots = myClient.getSpotMap();
      Map<Integer, List<AuctionObjectShadow>> bidderListBySpotID =
         new HashMap<Integer, List<AuctionObjectShadow>>();
      List<AuctionObjectShadow> auctionObjs =
         new LinkedList<AuctionObjectShadow>();
      List<GridAuctionObject> gridAuctionObjs =
         new LinkedList<GridAuctionObject>();

      List<AuctionObject> allBiddersList =
         myAuctionPool.getAuctionBidders(start, end);
      myCompetitorsByBuyId.clear();
      myUsedPriorities.clear();
      clearSnapshots();
      mySegments.clear();
      myAuctionSpots.clear();
      myNumWinners = 0;

      if (myDoSegmentDebug)
      {
         createSegmentDebugList();
      }

      // First, recalculate all buys' derived CPM values using the current
      // system impression lower threshold parameter. Also, record used
      // priorities.
      for (CampaignBuyAuctionInfo cb : myAuctionPool.getAllActiveCampaignBuys())
      {
         cb.recalculateDerivedCpm(myRemainImprLowerThreshPct);
         // Add our priority to the set of used priorities.
         myUsedPriorities.add(cb.auctionPriority);
         // If the buy is non-exclusive, bids can "fall through" from
         // higher priorities to lower. In this case, all the subsequent
         // priorities are potentially also used.
         if (!cb.isPriorityExclusive)
         {
            for (AuctionPass pass : myPassList)
            {
               int pri = pass.priority();
               if (pri > cb.auctionPriority)
               {
                  myUsedPriorities.add(pri);
               }
            }
         }
      }

      // Add client priority to our priority map.
      myUsedPriorities.add(myCampaignBuyAuctionInfo.auctionPriority);

      // Go through all the bidders for all the spots from the spot cache
      // to create AuctionObjectShadows.
      for (AuctionObject ao : allBiddersList)
      {
         CampaignBuyAuctionInfo cb = ao.campaignBuy;

         if (cb.campaignBuyID == myCampaignBuyId)
         {
            // Ignore any bids from our own CampaignBuy (this
            // would occcur if our Buy is Active). We will
            // create a new pool below.
            continue;
         }

         myCompetitorsByBuyId.add(cb.campaignBuyID);

         // Recalculate the bid's CPM values. This will pull the most
         // up-to-date value of the Buy's derived CPM.
         ao.calculate();

         // Create an AuctionObjectShadow object for this bidder.
         // Add it to lists of all bids for the spot and of all auction
         // participants.
         AuctionObjectShadow AO_shadow =
            new AuctionObjectShadow(ao, myCpmThreshold);

         addAOShadow(bidderListBySpotID, auctionObjs, AO_shadow);

         // Navic17596 - Previously we pruned the list of creatives
         // that don't have spot_ids during assignCpm(), however we
         // instantiate our SegmentSets before this. The
         // instantiation of a SegmentSet stores the OriginalList
         // of creatives associated with a bid. We do this to
         // restore a bid's list after pruning creatives for a
         // given Combo within the SegmentSet. Further, we use the
         // OriginalList within the SegmentSet whenever evaluating
         // a non-Linchpin as potential winner.
         // 
         // So, the bids in the SegmentSet will contain invalid
         // creatives (those without spot_ids) when a bid contains
         // an invalid creative. And that creative may be chosen as
         // a winner when the bid wins in a Combo of a SegmentSet
         // and is not a linchpin. This can occur in normal
         // SegmentSets passes or last-ditch passes.
         // 
         // The fix is to prune the list of creatives that don't
         // have spot_ids prior to instantiating the SegmentSets.
         // We'll only do this in real auctioning. For simulation,
         // we'll keep those invalid creatives in play.
         // 
         // Prune the invalid creatives from the bidder.
         // This'll set the state of the bid if there
         // aren't any creatives remaining for the bid.
         pruneCreatives(AO_shadow);
      }
      // 
      // Now create bidders from our grid's own spot shadows.
      //
      for (SpotShadow ss : ourGridSpots.values())
      {
         GridAuctionObject gridAO =
            new GridAuctionObject(myCampaignBuyAuctionInfo, ss);

         // The SpotShadow contains the subset list of creativeIds
         // that passed bid validation by the
         // AdBuySoup.checkValidation() method.
         AuctionObjectShadow gridAOshadow =
            new AuctionObjectShadow(gridAO, myCpmThreshold);

         gridAO.setAuctionParent(gridAOshadow);
         addAOShadow(bidderListBySpotID, auctionObjs, gridAOshadow);
         gridAuctionObjs.add(gridAO);
         myCampaignBuyAuctionInfo.addAuctionObject(gridAO);
      }
      myCampaignBuyAuctionInfo.optimize();

      // convert lists to arrays
      myClientAuctionObjects =
         gridAuctionObjs.toArray(new GridAuctionObject[0]);
      myAllAuctionObjects = auctionObjs.toArray(new AuctionObjectShadow[0]);

      // For each spot that has 1 or more interested bidders,
      // create an AuctionSpot and link its shadow bidders to it.
      Map<Integer, SegmentSet> segmentSetMap = new HashMap<Integer, SegmentSet>();
      for (Map.Entry<Integer, List<AuctionObjectShadow>> entry : bidderListBySpotID.entrySet())
      {
         int spotID = entry.getKey();
         Spot s = myAuctionPool.getSpotEntryById(spotID);
         AuctionSpot auctionSpot = new AuctionSpot(s);
         myAuctionSpots.put(spotID, auctionSpot);

         List<AuctionObjectShadow> biddersForSpot = entry.getValue();
         for (AuctionObjectShadow bid : biddersForSpot)
         {
            bid.auctionSpot = auctionSpot;
         }
         auctionSpot.bidders =
            biddersForSpot.toArray(new AuctionObjectShadow[0]);
      }

      // Set up mirror pairs and segments.
      for (AuctionSpot aSpot : myAuctionSpots.values())
      {
         if (aSpot.spot.isMirrored())
         {
            matchMirroredBids(aSpot);
         }

         // If this an avail in a segmented group of avails,
         // create a SegmentSet if necessary and add to it the Spot's bids.
         if (aSpot.spot.isSegmented())
         {
            createSegmentSet(aSpot, segmentSetMap);
         }
      }

      // Optimize SegmentSet data.
      for (SegmentSet segSet : segmentSetMap.values())
      {
         segSet.optimizeArrays();
      }
   }

   /**
    * Add an AuctionObjectShadow to various lists.
    * 
    * @param bySpotMap Map of SpotID -> List of AOShadows
    * @param allList List of AOShadows
    * @param aos AuctionObjectShadow to add
    */
   private void addAOShadow(
      Map<Integer, List<AuctionObjectShadow>> bySpotMap,
      List<AuctionObjectShadow> allList,
      AuctionObjectShadow aos)
   {
      int spotID = aos.auctionObj.spot.id;
      List<AuctionObjectShadow> spotBidderList = bySpotMap.get(spotID);
      if (spotBidderList == null)
      {
         spotBidderList = new LinkedList<AuctionObjectShadow>();
         bySpotMap.put(spotID, spotBidderList);
      }
      spotBidderList.add(aos);
      allList.add(aos);
   }

   /**
    * For a given mirrored AuctionSpot, find and assign all the mirrored bids to
    * their partner bids. Assumes that all AuctionSpots have been created and
    * the 'bidders' field on the AuctionSpot has been set up. Note that this
    * will process all bids in BOTH mirrored avails in a pair, and only does
    * work when it finds the partner in myAuctionSpots. If mirrored bids do not
    * have valid partners, they are permanently disqualified from participating
    * in the auction at all.
    * 
    * @param availArg AuctionSpot to process.
    */
   private void matchMirroredBids(AuctionSpot availArg)
   {
      Spot s = availArg.spot;
      int mirrorPartnerID = s.mirroredAvailID;
      AuctionSpot partnerAvail = myAuctionSpots.get(mirrorPartnerID);
      if (partnerAvail == null)
      {
         // Partner AuctionSpot does not exist. Mark all bids as disqualified.
         for (AuctionObjectShadow argBid : availArg.bidders)
         {
            argBid.setIsPermanentlyDisqualified(true,
               AuctionStatus.MIRRORED_SIBLING_AVAIL_ORPHAN);
         }
         return;
      }
      // We have a partner avail. For each bidder on this avail...
      for (AuctionObjectShadow argBid : availArg.bidders)
      {
         if (argBid.mirroredPartnerBid() != null)
         {
            // We've already set up this mirrored partner; nothing to do.
            continue;
         }
         int priBuyID = argBid.auctionObj.campaignBuy.campaignBuyID;
         // Find the matching bid by the same buy on the mirrored spot.
         boolean foundPartner = false;
         AuctionStatus losingReason = AuctionStatus.MIRRORED_SIBLING_BID_ORPHAN;
         for (AuctionObjectShadow partnerBid : partnerAvail.bidders)
         {
            if (priBuyID == partnerBid.auctionObj.campaignBuy.campaignBuyID)
            {
               // Found the mirror partner bid for same buy!
               // Make sure mirrored bid pair have creatives in common.
               if (!bidsHaveCommonCreatives(partnerBid, argBid))
               {
                  // Set partner status here. The argument's bid status will be
                  // set below.
                  losingReason = AuctionStatus.MIRRORED_BID_CREATIVES_PRUNED;
                  partnerBid.setIsPermanentlyDisqualified(true,
                     AuctionStatus.MIRRORED_BID_CREATIVES_PRUNED);
                  break;
               }
               // Happy path! Set each bid to be the other's mirrored partner.
               argBid.setMirroredPartnerBid(partnerBid);
               partnerBid.setMirroredPartnerBid(argBid);
               foundPartner = true;
               break;
            }
         }
         // if didn't find valid partner (with creatives), mark bid as lost.
         if (!foundPartner)
         {
            argBid.setIsPermanentlyDisqualified(true, losingReason);
         }
      }
   }

   /**
    * Make sure, in a pair of mirrored bids, there are creatives they have in
    * common, to allow the bids to participate in the auction at all. This
    * ensures that a winning bid will choose a creative that its mirrored
    * partner also has. Currently, loading ensures that both bids in mirrored
    * pair have the same set of creatives, but in case that changes, we check
    * whether EITHER has zero creatives.
    * 
    * @param bid1 to evaluate.
    * @param bid2 mirrored pair bid.
    * @return true if have at least 1 creative in common, false otherwise.
    */
   private boolean bidsHaveCommonCreatives(
      AuctionObjectShadow bid1,
      AuctionObjectShadow bid2)
   {
      if (bid1.auctionObj.creativeIds.size() == 0
         || bid2.auctionObj.creativeIds.size() == 0)
      {
         return false;
      }
      if (bid1.auctionObj.creativeIds.size() != bid2.auctionObj.creativeIds.size())
      {
         // Mismatched count (should have been pruned during loading). Internal
         // error? Let it go for now.
         theLogger
            .warn("Auctioneer.bidsHaveCommonCreatives(): mirrored bid pair with mismatched creatives count!");
      }
      return true;
   }

   /**
    * Check that segment data is set up for an avail; create SegmentSet if
    * necessary.
    * 
    * @param auctionSpot Segmented avail (may be root).
    * @param segmentSetMap Map of Root Avail ID to SegmentSet
    * @param bidsForSpot List of bids for this avail.
    */
   private void createSegmentSet(
      AuctionSpot auctionSpot,
      Map<Integer, SegmentSet> segmentSetMap)
   {
      Spot s = auctionSpot.spot;
      int segRootID = s.segmentRootID;
      SegmentSet segSet = segmentSetMap.get(segRootID);

      // Create one for this root ID if we haven't yet.
      if (segSet == null)
      {
         Spot root = myAuctionPool.getSpotEntryById(segRootID);
         segSet =
            new SegmentSet(root, (myDoSegmentDebug ? mySegmentDebugActions
               : null), mySegmentStats);
         segSet.setAllowsPartialWins(myAuctionSettings
            .allowPartialSegmentWins(s.breakView.orgId));
         segmentSetMap.put(segRootID, segSet);

         // If mirrored, attach mirrored partner SegmentSets.
         if (s.isMirrored())
         {
            // The ID of the partner is in the case also the segment root.
            int mirrorPartnerRootID = root.mirroredAvailID;
            SegmentSet mirrSegSet = segmentSetMap.get(mirrorPartnerRootID);
            // Only hook them up if we've got both of them.
            if (mirrSegSet != null)
            {
               mirrSegSet.setMirroredSegmentSet(segSet);
               segSet.setMirroredSegmentSet(mirrSegSet);
            }
         }
         mySegments.add(segSet);
      }
      // Add all this spot's bids to this SegmentSet's set of bids.
      for (AuctionObjectShadow bid : auctionSpot.bidders)
      {
         segSet.addBid(bid);
      }
      // Attach the SegmentSet to this AuctionSpot.
      auctionSpot.segmentSet = segSet;
   }

   /**
    * Create auction spending data from AuctionObjects in cache. Assumes
    * 'createAuctionObjects' has been run.
    */
   private void createAuctionSpendingData()
   {
      myBudget.initBudget(myAllAuctionObjects);
   }

   /**
    * Reset all auction state for all our AuctionObjectShadows.
    */
   private void resetAuctionValues()
   {
      // Reset bidder values
      for (AuctionObjectShadow aos : myAllAuctionObjects)
      {
         aos.resetValues();
      }
      // Reset preassigned winners state.
      for (PreassignedWinner preWin : myPreassignedWinners)
      {
         preWin.setAssignStatus(AssignStatus.UNASSIGNED);
      }

      myBudget.resetAuctionBudgetValues();
      myLastWinners.clear();

      myMaxConditionalAddToErrors = MAX_CONDITIONAL_ERRORS_PER_AUCTION;
      myMaxConditionalUnrollErrors = MAX_CONDITIONAL_ERRORS_PER_AUCTION;
   }

   /**
    * Reset the state of all bidders of unwon spots to be IN_PLAY. Should be
    * called between running auction passes, so any eligible bidders continuing
    * to the next pass can re-bid on the spots, at possibly lower prices.
    */
   private void resetUnsoldBidderStatus(AuctionPass curPass)
   {
      for (AuctionSpot as : myAuctionSpots.values())
      {
         // If this spot is segmented and there's a winner
         // of the segment, then we're done with this avail.
         // We used to set these bids to
         // HAS_SEGMENTED_WINNNER in the SegmentSet, but we'd
         // like to preserve other reasons for losing,
         // so that practice was abolished.
         if (as.spot.isSegmented() && as.segmentSet.foundWinner())
         {
            continue;
         }

         if (as.winner == null)
         {
            // This spot has no winners. Reset
            // eligible losers.
            for (AuctionObjectShadow bid : as.bidders)
            {
               // Special case not-in-program state (simulated auction only).
               // This state should not be reset - it is simulating a bid
               // that in a real auction would not even exist.
               if (!bid.auctionObj.isInProgram())
               {
                  continue;
               }
               // Don't reset bids that are permanently disqualified (e.g.
               // mirrored orphans).
               if (bid.isPermanentlyDisqualified())
               {
                  continue;
               }
               // If we have pruned all the creatives from this bid at this
               // point,
               // don't bother putting it back into subsequent passes of the
               // auction.
               // Since winners will not change, the adjacency attrs won't
               // either.
               // Resetting it here will result in its real losing reason being
               // overwritten.
               if (bid.getCreativeIds().size() == 0)
               {
                  continue;
               }
               // If this pass is beyond this bid's priority, we don't
               // want to erase the losing reason.
               // Only reset to IN_PLAY if this bid is eligible for this pass.
               boolean isQual = curPass.isBidPriorityQualified(bid);
               if (isQual)
               {
                  bid.auctionState = AuctionStatus.IN_PLAY;
                  bid.setLastPriority(curPass);
               }
               // Set the flag on the bid here.
               bid.setIsCurPriorityQualified(isQual);
            }
         }
      }
   }

   /********************************************
    * AUCTION ALGORITHMS
    ********************************************/

   /**
    * Run an auction for all known bidders over the client's date range.
    */
   public void runAuction()
   {
      runAuction(AuctionType.SINGLE);
   }

   /**
    * Run an auction for all known bidders over the client's date range. Allow
    * only one auction to run at a time for this Auctioneer instance.
    * 
    * @param aType AuctionType of this auction run.
    */
   public synchronized void runAuction(AuctionType aType)
   {
      resetStats(aType);
      startStatsTiming();

      setDateRange();

      doAuction();

      endStatsTiming();
   }

   /**
    * MAIN ALGORITHM Perform an auction, given the current settings.
    */

   private void doAuction()
   {
      // PRE-AUCTION: Initialize values
      resetAuctionValues();
      recalculateGridValues(myCurToggles);

      // Set any pre-assigned winners.
      handlePreassignedWinners(AuctionPass.PREASSIGN_WINNER_PASS);

      // Run each pass of the auction
      for (AuctionPass pass : myPassList)
      {
         // Efficiency: if we have no buys with this priority,
         // don't run this pass of the auction.
         if (!myUsedPriorities.contains(pass.priority()))
         {
            continue;
         }
         // Reset the status on all bids on unsold
         // avails that are eligible for this pass.
         resetUnsoldBidderStatus(pass);
         // Run this pass
         doSingleAuctionPass(pass);
      }

      // Done. Mark bids that did not participate, for debugging.
      markNonparticipants(AuctionPass.POST_AUCTION_PASS);

      // Set the results for querying.
      setAuctionResults();
   }

   /**
    * Run a single auction pass. A full auction runs the auction for only
    * preferred bids FIRST, then a general pass for all bids, retaining the
    * results of the preferred pass.
    * 
    * @param pass AuctionPass we are currently running.
    */
   private void doSingleAuctionPass(AuctionPass pass)
   {
      //
      // STEP 1: ASSIGN CPM to all bidders of
      // each auctionable spot...
      for (AuctionSpot aSpot : myAuctionSpots.values())
      {
         assignCpm(aSpot, pass);
      }
      myStats.timestamp("CPMs-ASSIGNED-Pass-#" + pass.name());

      //
      // STEP 2: ASSIGN WINNERS
      // We order entire bid list (instead of ordering per spot).
      // This gives clients the opportunity to win their highest-ranked
      // spots FIRST, rather than eating up their budget on lower spots.
      List<AuctionObjectShadow> list2 =
         rankBidders(myAllAuctionObjects, RANK_WINNER_DESC_COMPARATOR);

      // The segmented avails contain ranked lists that are sorted
      // by rank & cost. Since the cost may have changed due to
      // assignCpm we need to re-sort these ranked lists.
      sortSegmentSets();

      for (int index = 0; index < list2.size(); index++)
      {
         // Record where this bid is in the ranked list for logging.
         AuctionObjectShadow bid = list2.get(index);
         bid.biddingIndex = index;

         // First, skip over any bids that have already won in a prior pass
         if (bid.auctionState == AuctionStatus.WINNER)
         {
            continue;
         }

         // Second, check all our winner qualifications.
         if (!canBeWinner(bid, pass))
         {
            // Add some debug info if debugging is turned on.
            setAuctionDebugAction(bid, false);
            continue;
         }
         // We (probably) have a winner.
         // Maybe. Handles mirroring, segmented avails
         // or any other special cases for a given bid. If the bid
         // can't win in handleWinner, it will have its auction state
         // set appropriately.
         handleWinner(list2, bid, pass);

         // handleWinner() could have applied conditional totals
         // so we need to check again here.
         // Yes, we check for conditional totals in setAsWinner()
         // but this bid may not have won due to segmentation,
         // mirroring or bundling.
         logErrorIfHasConditionalTotals(bid);
      }

      // Perform tasks associated with the end of pass.
      auctionPassComplete(pass);

      myStats.timestamp("WINs-ASSIGNED-Pass-#" + pass.name());
   }

   /**
    * Assign a CPM value to each bidder of an AuctionSpot. Implements the first
    * step of the Auction algorithm.
    * 
    * @param aSpot AuctionSpot to be processed.
    * @param pass Which pass is being executed.
    */
   private void assignCpm(AuctionSpot aSpot, AuctionPass pass)
   {
      // PRE-CPM: If no viewers, don't bother with this spot at all.
      if (!anyImpressionsPasses(aSpot))
      {
         return;
      }
      // PRE-CPM: If the spot already won on a prior pass, bail out.
      if (aSpot.winner != null)
      {
         return;
      }
      // PRE-CPM: If this is in a Segment that has already found winner(s),
      // ignore.
      if (aSpot.segmentSet != null && aSpot.segmentSet.foundWinner())
      {
         return;
      }
      // CPM Setup
      // Initial bid is policy price plus navic commission for this pass.
      Spot s = aSpot.spot;
      Money minBidPrice = pass.calculateMinBidCost(s);
      // Note: totalADViews was checked above and should never be zero!
      float minAvailCpm =
         AuctionUtils.calculateCPM(s.totalADViews, minBidPrice);

      // STEP 1a: Order the bidders for this spot by rank.
      List<AuctionObjectShadow> bidList =
         rankBidders(aSpot.bidders, RANK_ASCEND_COMPARATOR);

      // STEP 1b: Assign CPM to each bidder on this spot
      float curBidCpm = minAvailCpm;
      AuctionObjectShadow lastBid = null;
      for (AuctionObjectShadow bid : bidList)
      {
         // Record min bid value for logging.
         bid.lastMinBidPrice = minBidPrice;

         // If this bidder's priority level does not
         // qualify for this pass, skip it.
         if (!canBidderParticipate(bid, pass))
         {
            continue;
         }
         // Mark the bid as having been "seen"--i.e., it got into an auction,
         // even if it got kicked out again immediately.
         bid.isSeen = true;

         // Check various flags on the buy and set the actual CPM for this bid.
         setActualBidCpm(bid, curBidCpm, minAvailCpm);

         // STEP 1c: check CPM threshold.
         // Note: this method MAY change the bid's actualCPM value.
         boolean ok = cpmPasses(bid, lastBid, minAvailCpm);
         if (ok)
         {
            // STEP 1d: check efficiency threshold here
            ok = efficiencyPasses(bid);
         }
         if (ok)
         {
            // STEP 1e: check creative(s) here
            ok = pruneCreatives(bid);
         }
         // Set the bid for this spot by this Ad Buy.
         if (ok)
         {
            // Set the proposed CPM for the next bidder on this spot:
            // use the derived CPM (what the current bidder was
            // willing to pay), plus the auction win margin (typically, $1).
            curBidCpm = bid.auctionObj.derivedCPM() + myAuctionWinMargin;
            // We never want the next bid's proposed CPM to be less than the
            // minimum for the spot. This can happen because we allow a
            // "slop factor" when calculating affordability--the current
            // bidder might have a lower derived CPM than strictly required.
            if (curBidCpm < minAvailCpm)
               curBidCpm = minAvailCpm;
         }
         lastBid = bid;
      }
   }

   /**
    * Check all the ways a bid can lose, and if it does lose, assign the reason
    * to the bid's status. This bid must NOT have conditional totals applied to
    * it or an error will be logged against that bid.
    * 
    * @param bid Bid to check.
    * @param pass Which pass we are executing.
    * @return the creativeId to use as the winning creative for the bid, or -1
    *         if the bid can not win.
    */
   public boolean canBeWinner(AuctionObjectShadow bid, AuctionPass pass)
   {
      // Log an error if this bid has conditional totals applied.
      // We should never apply conditional totals to a bid
      // before we evaluate if it canBeWinner().
      logErrorIfHasConditionalTotals(bid);

      // STEP 1: check if this bidder should not be participating.
      if (!canBidderParticipate(bid, pass))
      {
         return false;
      }
      // STEP 2: BUILT-IN Check auto-adjacency rules
      if (!autoAdjacencyPasses(bid))
      {
         return false;
      }
      // STEP 3: Check advertiser's adjacency rules
      if (!advertiserAjacencyPasses(bid))
      {
         return false;
      }
      // STEP 4: Check time-based proximity restrictions on channel
      if (!proximityRestrictionPasses(bid))
      {
         return false;
      }
      // STEP 5: Check advertiser budget constraints
      if (!budgetLimitsPass(bid))
      {
         return false;
      }
      // STEP 6: Check product type adjacency constraints
      // This will potentially prune the bid's list of creatives.
      // We'll need to restore the list before returning from
      // this method.
      boolean canWin = productAdjacencyPasses(bid);
      if (canWin)
      {
         // STEP 7: Evaluate the creative rotation constraints
         // and set the creativeId.
         canWin = creativeRotationPasses(bid);
      }

      // STEP 8: Restore the original list of creativeIds from the AuctionObject.
      bid.restoreCreativeIds();

      return canWin;
   }

   /**
    * Code that determines any special winner code to use on a bid that has been
    * determined to be able to be a winner. It is possible that the bid may not
    * be marked a winner as a result of other constraints (of mirroring or
    * segmentation) that are handled here.
    * 
    * @param rankedList The ordered list of bids being traversed. The
    *        <code>bid.biddingIndex</code> field gives the index of the bid in
    *        this list.
    * @param bid Possibly winning bid.
    * @param pass Auction pass we're on.
    */
   private void handleWinner(
      List<AuctionObjectShadow> rankedList,
      AuctionObjectShadow bid,
      AuctionPass pass)
   {
      AuctionSpot as = bid.auctionSpot;

      // Note below that each check indicates a mutually-exclusive
      // condition, handles it, and returns. Any supported combinations
      // (for example, segmented mirrored avails) must be accounted for
      // within the 'handle' method.

      // Segmented spots are handled separately.
      // (Note: this will handle segmented mirrors, so we must
      // check isSegmented before checking isMirrored. )
      if (as.spot.isSegmented())
      {
         as.segmentSet.handleSegmentedWinner(this, bid, pass);
         return;
      }
      // ... as are bids for avails with channel bundling requirements ...
      // (Note: Segments are not supported, so this must come
      // after the segment test. Mirrored bids are not handled
      // here either, although CB look-ahead mirrored bids are considered.
      if (bid.hasChannelBundlingReq())
      {
         handleChanBundlingReqWinner(rankedList, bid, pass);
         return;
      }
      // ... as are mirrored spots.
      if (as.spot.isMirrored())
      {
         handleMirroredWinner(bid, pass, WinType.NORMAL);
         return;
      }

      // A normal bid winner.
      setAsWinner(bid, pass);
   }

   /**
    * Set a bid's "Actual" CPM. There are a number of flags on the Campaign Buy
    * that may alter the normal determination of the price a buy will bid for an
    * avail. Normally, the price is determined by the previously ranked bid's
    * derived CPM, but the flags in this method may override these. All flags
    * are mutually exclusive.
    * 
    * @param bid Bid whose actual CPM will be set.
    * @param curCpm The current CPM in the context of the auction.
    * @param minAvailCpm Minimum bid CPM for this avail.
    */
   private void setActualBidCpm(
      AuctionObjectShadow bid,
      float curCpm,
      float minAvailCpm)
   {
      CampaignBuyAuctionInfo buy = bid.auctionObj.campaignBuy;

      // Check for flag that overrides competitive price & allows it to pay the
      // minimum.
      if (buy.paysMinAvailRate)
      {
         bid.setActualCPM(minAvailCpm, PricingType.MINIMUM_AVAIL_RATE);
      }
      // Check for flag that allows it to pay a fixed advertiser rate
      // (Optimization Tool).
      else if (buy.paysAdvertiserAdjustRate)
      {
         float advCpm = buy.calcAdvertiserAdjustedCPM(minAvailCpm);
         bid.setActualCPM(advCpm, PricingType.ADV_MARKUP);
      }
      // Check for flag that allows it to pay exactly the CPM set by the
      // advertiser.
      // used to emulate the online ad sales model.
      // NOTE: Current implementation does NOT apply fixed CPM to auction price!
      // else if (buy.paysFixedCPM)
      // {
      // bid.setActualCPM(buy.fixedCPM, PricingType.ADV_TARGET_CPM);
      // }
      else
      {
         // Use the competitively-calculated CPM for this bid.
         bid.setActualCPM(curCpm, PricingType.COMPETITIVE);
      }
   }

   /**
    * Perform all steps necessary after an Auction Pass has completed.
    * 
    * @param pass
    */
   private void auctionPassComplete(AuctionPass pass)
   {
      if (pass.doSegmentLastResort())
      {
         // If any segment hasn't won, determine if we can find
         // any winning combo for it now.
         hailMaryPassSegmentSets(pass);
      }

      // At this point, mark any segments that didn't win.
      markAllUnwonSegmentBids(pass);

      // Make snapshot of all bids if requested.
      snapshotAllBids(pass);
   }

   /**
    * Special processing for bundling (minimum wins per channel requirement).
    * For a given bid that is assumed to be winnable, if we cannot find minimum
    * number of wins from the same buy on avails owned by orgs that are eligible
    * to fulfill this avail's bundling requirement, the bid fails. If we do find
    * a winner, set all bids as winners.
    * 
    * @param rankedBidList List to iterate through.
    * @param winBid Bid being evaluated. Has already been judged as able to be a
    *        winner itself.
    * @param pass Which pass we are executing.
    */
   private void handleChanBundlingReqWinner(
      List<AuctionObjectShadow> rankedBidList,
      AuctionObjectShadow winBid,
      AuctionPass pass)
   {
      Spot winSpot = winBid.auctionObj.spot;
      // Mirrors and segments not handled in here. Make sure there's no
      // misconfiguration.
      assert (!winSpot.isMirrored());
      assert (!winSpot.isSegmented());

      CampaignBuyAuctionInfo winBuy = winBid.auctionObj.campaignBuy;
      ChannelBundlingInfo bundleInfo = myBudget.getChannelBudgetInfo(winBuy);
      BreakView bv = winSpot.breakView;
      int winOrgID = bv.orgId;

      // First, check whether the bundling requirement has already been met.
      // If so, just set bid as winner and return.
      if (bundleInfo.isChannelBundlingReqMet(winOrgID))
      {
         setAsWinner(winBid, pass);
         return;
      }
      // Set of channels we have accumulated so far.
      Set<Integer> chanIDs = bundleInfo.getChannelsFulfilled(winOrgID);
      // Get our bundling requirement for this avail's org.
      int minChanCount = bundleInfo.getMinChannelWinReqForOrg(winOrgID);
      // We already have the channels we need from other orgs.
      if (chanIDs.size() >= minChanCount)
      {
         // Set this flag so we don't have to check next time.
         bundleInfo.setChannelBundlingReqMet(winOrgID);
         setAsWinner(winBid, pass);
         return;
      }
      // At this point, we have an unsatisfied bundling requirement for this
      // org.

      // A bid on a future avail cannot be used to satisfy bundling reqs during
      // a real auction: win fails.
      if (winSpot.isFuture && isRealAuction())
      {
         winBid.auctionState = AuctionStatus.CHANNEL_BUNDLING_REQ_NOT_MET;
         return;
      }
      // Set of winnable bids that will fulfill the channel bundling
      // requirement.
      List<AuctionObjectShadow> tempWinners =
         new ArrayList<AuctionObjectShadow>();

      // Assume this bid has won: update budget totals here, so that
      // budget checks on other avails will take into account this win.
      addToConditionalTotals(winBid);

      // Add winner to channel and temp winner lists.
      chanIDs.add(bv.channelId);
      tempWinners.add(winBid);

      // Try to find another win by the buy for an avail that qualifies for
      // channel bundling from our ranked list, starting from our next index.
      int startIndx = winBid.biddingIndex + 1;
      for (int i = startIndx; i < rankedBidList.size(); i++)
      {
         // Get out of the loop if we've fulfilled the requirement.
         if (chanIDs.size() >= minChanCount)
         {
            break;
         }
         AuctionObjectShadow other = rankedBidList.get(i);
         // If other bid is not from our buy, ignore. Note we compare object
         // refs.
         if (other.auctionObj.campaignBuy != winBuy)
         {
            continue;
         }
         Spot otherSpot = other.auctionObj.spot;
         // Ignore segmented bids.
         if (otherSpot.isSegmented())
         {
            continue;
         }
         // For simulated auctions we will include future bids in bundling,
         // but for real auctions we will ignore futures.
         if (otherSpot.isFuture && isRealAuction())
         {
            continue;
         }
         
         // If we already have this channel in our list, ignore.
         BreakView otherBv = otherSpot.breakView;
         int otherChanID = otherBv.channelId;
         if (chanIDs.contains(otherChanID))
         {
            continue;
         }

         // If the other bid's avail is not eligible to fulfill our
         // avail's org channel bundling requirements, skip this.
         int otherOrgID = otherBv.orgId;
         boolean isOtherEligible =
            bundleInfo.canOrgInventoryBeUsedToBundle(winOrgID, otherOrgID);
         if (!isOtherEligible)
         {
            continue;
         }

         // If this other bid of ours can win,...
         AuctionStatus prevState = other.auctionState;
         if (canBeWinner(other, pass))
         {
            // Add cost to our totals here, in case it affects mirror partner
            // canBeWinner().
            addToConditionalTotals(other);

            // Handle the mirroring case here.
            if (otherSpot.isMirrored())
            {
               AuctionObjectShadow partnerBid =
                  getInPlayMirroredPartnerBid(other);
               if (partnerBid == null)
               {
                  // There is no partner bid; ignore this bid.
                  unrollConditionalTotals(other);
                  continue;
               }
               AuctionStatus prevPartnerState = partnerBid.auctionState;
               if (!canBeWinner(partnerBid, pass))
               {
                  // Mirrored partner fails to win for some reason.
                  // Erase any error state set by canBeWinner(), and continue
                  // looking.
                  partnerBid.auctionState = prevPartnerState;
                  unrollConditionalTotals(other);
                  continue;
               }
               // Add mirror partner to our list of winners & totals.
               addToConditionalTotals(partnerBid);
               tempWinners.add(partnerBid);

            } // END ifMirrored

            // Now add this to our list of winners.
            tempWinners.add(other);
            // Add this channel to the set we already have.
            // Note: any mirrored bid would be on the same channel, so
            // we don't need this in the mirror partner section above.
            chanIDs.add(otherChanID);
         }
         // Restore any status change on this other bid from canBeWinner() test.
         other.auctionState = prevState;

      } // End of iteration through ranked bid list

      // We either got to the end of the list, or found our required bids.
      // Unroll all conditional wins (including original win).
      for (AuctionObjectShadow other : tempWinners)
      {
         unrollConditionalTotals(other);
      }

      // If found winners on at least the minimum # of required channels, we
      // won!
      if (chanIDs.size() >= minChanCount)
      {
         // Set all actual wins with bundling win type.
         for (AuctionObjectShadow other : tempWinners)
         {
            setAsWinner(other, pass, WinType.BUNDLING_REQ);
         }
         // We're done.
         return;
      }

      // We didn't find a win to satisfy the channel bundling requirement for
      // this bid,
      // so record the losing reason for this bid.
      winBid.auctionState = AuctionStatus.CHANNEL_BUNDLING_REQ_NOT_MET;
   }

   /**
    * Special processing for a mirrored bid. For a given bid on a mirrored Spot,
    * get the bid's Spot's mirrored partner, and the matching bid by the same
    * buy. If the mirrored bid fails to be a winner, this bid also fails. If the
    * mirrored bid wins, set both bids as winners.
    * 
    * @param mBid Bid to evaluate. Has already been judged as able to be a
    *        winner itself.
    * @param pass Which pass we are executing.
    */
   public void handleMirroredWinner(
      AuctionObjectShadow mBid,
      AuctionPass pass,
      WinType wType)
   {
      // Find mirrored partner bid or fail.
      // Note that mBid will be marked with any failure status.
      AuctionObjectShadow partnerBid = getInPlayMirroredPartnerBid(mBid);
      if (partnerBid == null)
      {
         return;
      }
      // At this point, assume the original bid has won. Update budget totals
      // here, so that
      // budget checks on mirror sibling will take into account this win.
      addToConditionalTotals(mBid);
      boolean canWin = false;

      // We must use the same creative as was used in the original bid.
      // We need to do this before canBeWinner() is called so that we
      // evaluate product attributes for the appropriate creative.
      partnerBid.setCreativeIds(mBid.getSelectedCreativeId());

      // Now test the sibling bid as we already have tested this one.
      // 'canBeWinner' will have set the state of the sibling bid
      // if it lost. We need to preserve this.
      if (canBeWinner(partnerBid, pass))
      {
         canWin = true;
      }
      //
      // At this point, whether we won or not, we want to
      // unroll the budget calculations made above.
      // The 'setAsWinner()' calls below will
      // update the budgets normally for both winners.
      unrollConditionalTotals(mBid);

      if (canWin)
      {
         // We won both! Set ourselves and our
         // partner's bids to be winners, which includes budget updates.
         setAsWinner(mBid, pass, wType);
         // Make sure that the partner win type is MIRR_PARTNER
         // and it gets the same creativeId as the winner.
         partnerBid.setSelectedCreativeId(mBid.getSelectedCreativeId());
         setAsWinner(partnerBid, pass, WinType.MIRR_PARTNER);
      }
      else
      {
         // We lost. Mark the calling bid. The losing partner bid will
         // have had its state already set.
         mBid.auctionState = AuctionStatus.MIRRORED_SIBLING_BID_LOST;
      }
      // Done with this bid.
   }

   /**
    * Given a mirrored bid, return its mirrored partner bid that is still in
    * play. Return null on any error and set the failure reason on the mirrored
    * bid.
    * 
    * @param mBid Bid to find mirrored partner for.
    * @return Bid by the same buy on the mirrored partner spot, or null if
    *         either we can't find this bid or the bid is no longer in play.
    */
   public AuctionObjectShadow getInPlayMirroredPartnerBid(
      AuctionObjectShadow mBid)
   {
      // We have already checked the eligibility of this bid itself
      // ('canBeWinner()').
      // At this point, this bid on an mirrored avail COULD be a winner,
      // as long as this buy can win its mirrored partner avail.
      AuctionObject ao = mBid.auctionObj;
      // Get our partner.
      Spot mirrorSpot = ao.spot.mirroredPartner();
      // If we have no mirror partner spot, this is really a data error, but
      // we'll report this as a mirror orphan.
      if (mirrorSpot == null)
      {
         mBid.auctionState = AuctionStatus.MIRRORED_SIBLING_AVAIL_ORPHAN;
         return null;
      }

      AuctionSpot auctionSibling = myAuctionSpots.get(mirrorSpot.id);
      if (auctionSibling == null)
      {
         // A sibling avail may not be in our auction pool (if its total views
         // is 0, there may be no bids on it, for example). If this is the
         // case, we cannot win this bid. Bail out.
         mBid.auctionState = AuctionStatus.MIRRORED_SIBLING_AVAIL_ORPHAN;
         return null;
      }
      // Find mirrored partner bid or fail.
      AuctionObjectShadow partnerBid = mBid.mirroredPartnerBid();
      if (partnerBid == null)
      {
         mBid.auctionState = AuctionStatus.MIRRORED_SIBLING_BID_ORPHAN;
         return null;
      }
      if (partnerBid.auctionState != AuctionStatus.IN_PLAY)
      {
         // The partner is an earlier bid that failed. This bid is disqualified.
         mBid.auctionState = AuctionStatus.MIRRORED_SIBLING_BID_LOST;
         return null;
      }
      return partnerBid;
   }

   /**
    * The segmented avails contain ranked lists that are sorted by rank & cost.
    * Since the cost may have changed due to assignCpm we need to re-sort these
    * ranked lists.
    */
   private void sortSegmentSets()
   {
      for (SegmentSet ss : mySegments)
      {
         ss.sortSegment();
      }
   }

   /**
    * Attempt to find any possible winners for any SegmentSets that have not yet
    * won.
    */
   private void hailMaryPassSegmentSets(AuctionPass pass)
   {
      // Sort our segments by day, so we assign earlier winners first.
      List<SegmentSet> orderedSegments = new ArrayList<SegmentSet>();
      orderedSegments.addAll(mySegments);

      Collections.sort(orderedSegments, new Comparator<SegmentSet>()
      {
         public int compare(SegmentSet ss1, SegmentSet ss2)
         {
            Integer bdi1 = ss1.myRootAvail.gridDayIndex;
            Integer bdi2 = ss2.myRootAvail.gridDayIndex;
            return bdi1.compareTo(bdi2);
         }
      });
      for (SegmentSet ss : orderedSegments)
      {
         ss.auctionComplete(this, pass);
      }
   }

   /**
    * Mark IN_PLAY bids in each segment as non-winners.
    */
   private void markAllUnwonSegmentBids(AuctionPass pass)
   {
      for (SegmentSet ss : mySegments)
      {
         ss.markAllSegmentBidsLost();
      }
   }

   /**
    * Go through the bids. Any bid that was IN_PLAY but never participated in an
    * auction - if, for example, its auction priority isn't in valid - should be
    * marked as NEVER_PARTICIPATED_IN_AUCTION.
    */
   private void markNonparticipants(AuctionPass pass)
   {
      for (AuctionObjectShadow bid : myAllAuctionObjects)
      {
         if (!bid.isSeen && bid.auctionState == AuctionStatus.IN_PLAY)
         {
            bid.auctionState = AuctionStatus.NEVER_PARTICIPATED_IN_AUCTION;
            bid.setLastPriority(pass);
         }

      }
   }

   /********************************************************
    * AUCTION CONDITIONS SECTION
    ********************************************************/

   /**
    * Check whether this bidder is qualified to participate in this auction 
    * for a given pass.  Check whether the bid is IN_PROGRAM for simulated bids, is
    * still in play, and is qualified to participate in this pass. 
    * @param bidder AuctionObjectShadow to consider.
    * @param pass Which pass we are currently executing.
    * @return true if the bidder is OK, false if it should not participate in the current auction.
    */
   private boolean canBidderParticipate(AuctionObjectShadow bidder,
                                        AuctionPass pass)
   {
       // Special case bids that are NOT in program (simulated auction only).
       // In real auctions, out-of-program bids won't appear, so this bid shouldn't
       // even be assigned a CPM. We check here first so state is not overwritten.
       if (!bidder.auctionObj.isInProgram())
       {
           return false;
       }
       // If this bidder's spot has been won, it should already be marked as
       // HAS_WINNER, and if our state has already been set to any other error
       // (e.g., mirror partner orphan, failed demographics, etc.), we fail.
       if (bidder.auctionState != AuctionStatus.IN_PLAY)
       {
           return false;
       }
       // If this in-play bid does not qualify for this pass, return false.
       if (!bidder.isCurPriorityQualified())
       {
           // Setting the status here can mask the losing reason in the final report,
           // we do not report this status.  Previous code:
           // bidder.auctionState = AuctionStatus.PRIORITY_DISQUALIFIED;
           return false;
       }
       // We are good to go!
       return true;
   }

   /**
    * Check whether this spot has any viewers. If not, the status for all the
    * bidders are set accordingly.
    * 
    * @return true if there are any impressions for this spot. If false, set the
    *         bidder status to the appropriate failure.
    */
   private boolean anyImpressionsPasses(AuctionSpot aSpot)
   {
      if (aSpot.spot.totalADViews == 0)
      {
         myStats.zeroViewerSpots++;
         for (AuctionObjectShadow bidder : aSpot.bidders)
         {
            bidder.auctionState = AuctionStatus.NO_VIEWERS;
         }
         return false;
      }
      return true;
   }

   /**
    * Check whether this bid qualifies for a rate card discount. If this spot
    * has only one bid (and the rate card price is less than the standard),
    * calculate the spot's discounted CPM using the Rate Card Threshold. If the
    * buy can afford the discount rate, set this new rate as the bidder's CPM.
    * This method assumes the bid has already been checked for the normal price.
    * 
    * @param bidder Bid for a spot.
    * @return true if this bid qualifies for the discount, false if not.
    */
   private boolean bidQualifiesForRateCardDiscount(AuctionObjectShadow bidder)
   {
      AuctionSpot aSpot = bidder.auctionSpot;
      if (aSpot.bidders.length > 1)
      {
         return false;
      }
      Spot s = aSpot.spot;
      int orgID = s.breakView.orgId;
      Integer ratePct = myRateCardDiscountsByOrg.get(orgID);
      if (ratePct == null || ratePct >= 100)
      {
         return false;
      }
      Money discountPrice = s.minimumPrice().percent(ratePct);
      float discountCPM =
         AuctionUtils.calculateCPM(bidder.derivedCpmThreshold, s.totalADViews,
            discountPrice);
      if (bidder.derivedCpmThreshold >= discountCPM)
      {
         bidder.setActualCPM(discountCPM, PricingType.SINGLE_BID_DISCOUNT);
         bidder.setIsDiscount(true, ratePct);
         // Adjust the minimum bid price for this bid.
         bidder.lastMinBidPrice = discountPrice;
         return true;
      }
      return false;
   }

   /**
    * Check whether the efficiency of this spot is less than the efficiency
    * threshold of this Ad Buy. The threshold efficiency is the system-wide
    * efficiency threshold times the Ad Buy's baseline (average) efficiency.
    * 
    * @param bidder AuctionObject
    * @return true if the spot is above the Ad Buy's efficiency threshold. If
    *         false, set the bidder's status to reflect that the spot is too
    *         inefficient. to the appropriate reason for failure.
    */
   private boolean efficiencyPasses(AuctionObjectShadow bidder)
   {
      AuctionObject ao = bidder.auctionObj;
      float spotEfficiency = ao.spotEfficiency();
      int thresholdPct = 100 - myEfficiencyThrPct;
      float minEff = (ao.campaignBuy.baselineEfficiency * thresholdPct) / 100;
      if (spotEfficiency < minEff)
      {
         bidder.auctionState = AuctionStatus.EFFICIENCY_BELOW_THRESHOLD;
         return false;
      }
      return true;
   }

   /**
    * Checks a bid's creative(s): Ad buys in real auctions must have at least 1
    * creative with an assigned local Spot ID, and the duration of the creative
    * must match the duration of the spot avail. For each creative, if either
    * there is no spot ID on it or it is not propagated to the avail's org, then
    * remove the creative from the bid's list (UNLESS this is our (grid) buy in
    * a simulated auction - we allow creatives that have not been approved). If
    * there are no creatives left in the bid's list by the end, return false and
    * mark the bid appropriately with the failure reason. This does NOT assign
    * the creative for the bid.
    * 
    * @param bidder AuctionObject
    * @return true if spot and ad durations match, and at least one creative has
    *         a spot ID and is propagated to local. If false, set the bidder's
    *         status to the appropriate reason for failure.
    */
   private boolean pruneCreatives(AuctionObjectShadow bidder)
   {
      AuctionObject ao = bidder.auctionObj;
      CampaignBuyAuctionInfo cb = ao.campaignBuy;
      boolean cbHasCreatives = (cb.creativeIDs().size() > 0);

      // Check whether we have a creative assigned to the bidder's buy.
      if (!cbHasCreatives)
      {
         if (myCampaignBuyId == cb.campaignBuyID)
         {
            // Client buy, but no creative assigned yet.
            // We'll assume an appropriate length spot will be
            // assigned and pass. This path may be obsolete now.
            return true;
         }
         else
         {
            // A system buy that has no spot assigned yet is an error.
            bidder.auctionState = AuctionStatus.NO_CREATIVE_ID;
            return false;
         }
      }
      // The bid's list of creatives is a subset of the CB's list.
      // Getting here means that there are creatives associated with the CB.
      Collection<Integer> bidCreativeIds = bidder.getCreativeIds();
      boolean bidHasCreatives = (bidCreativeIds.size() > 0);
      if (!bidHasCreatives)
      {
         // The bid's list of creatives got pruned
         // of all creatives before we started pruning!
         bidder.auctionState = AuctionStatus.NO_BID_CREATIVE_ID;
         return false;
      }

      Spot spot = ao.spot;

      // Navic16947 - Allow a Media Buy with a Creative that is not
      // yet approved to win in simulated auctioning, and o not take
      // these bids out of the auction. However, do NOT allow a
      // REJECTED creative to win in a simulated auction.
      // The thought is that all active Media Buys WILL have their
      // creatives approved, so they should be allowed to compete in
      // simulation.
      // Previously, we would remove these creatives (& bids if the
      // creative list became empty)
      boolean isReal = isRealAuction();

      // System bids must have an Advertiser Spot ID assigned
      // by the spot's destination MSO. The buy's creative may be:
      // 1. Not yet propagated from central to local
      // 2. Not approved (previously, this checked for the creative
      // having a Spot ID, but approval requires a Spot ID now, so
      // that check is implied).
      // Check for both.

      // Prune the bidder's list of creatives that don't qualify.

      int orgId = spot.breakView.orgId;

      AuctionStatus lastState = AuctionStatus.IN_PLAY;
      Iterator<Integer> iter = bidCreativeIds.iterator();
      while (iter.hasNext())
      {
         int creativeId = iter.next();

         // Bid is actually REJECTED by org; fail for both real & simulated
         // auctions.
         // Check this first before the not-propagated check, since this may
         // affect that.
         if (cb.isCreativeRejectedByOrg(creativeId, orgId))
         {
            lastState = AuctionStatus.CREATIVE_NOT_APPROVED;
            iter.remove();
            continue;
         }

         // If a real auction, the bid with this creative should not have been
         // created if not propagated, but we check anyway. If simulated,
         // we allow non-propagated creatives for grid buys.
         if (isReal && !cb.isCreativePropagatedToOrg(creativeId, orgId))
         {
            lastState = AuctionStatus.CREATIVE_NOT_PROPAGATED_TO_LOCAL;
            iter.remove();
            continue;
         }

         // For simulated auctions, we allow PENDING creatives;
         // not so for real auctions.
         if (isReal && !cb.isCreativeApprovedByOrg(creativeId, orgId))
         {
            lastState = AuctionStatus.CREATIVE_NOT_APPROVED;
            iter.remove();
            continue;
         }
      }

      if (bidCreativeIds.isEmpty())
      {
         // We pruned all the creatives from the bid.
         // Record the last losing reason on the bid
         bidder.auctionState = lastState;
         return false;
      }

      // If we have any creatives remaining, then the bid
      // passes the creative check.

      // We have a creative; check that duration matches the avail here.
      if (cb.creativeDuration != spot.duration)
      {
         bidder.auctionState = AuctionStatus.MISMATCHED_DURATION;
         return false;
      }
      return true;
   }

   /**
    * Check whether spot CPM's, based on a current bid, would be (cpmThreshold
    * %) greater than the bidder's Ad Buy's derived CPM, that is, would be too
    * expensive. Adjust bid as necessary, or return false if bid cannot be made.
    * May change 'actualCPM' of bidder.
    * 
    * @param bidder AuctionObjectShadow.
    * @param prev Previous bidder.
    * @param minSpotCpm minimum CPM on spot
    * @return true if bid would qualify. If false, the bidder's status is set to
    *         the appropriate reason for failure.
    */
   private boolean cpmPasses(
      AuctionObjectShadow bidder,
      AuctionObjectShadow prev,
      float minSpotCpm)
   {
      float curBidCpm = bidder.actualCPM();
      float prevActualCpm = (prev == null ? 0.0f : prev.actualCPM());
      float curAdBuyCpmThreshold = bidder.derivedCpmThreshold;
      // Check whether current bid exceeds threshold of this buy.
      // If so, set it to CPM+Threshold if:
      // 1. CPM + threshold > minimum CPM (we can afford the avail at all)
      // 2. CPM + threshold > previous ranked bid's actual CPM.
      if (curBidCpm > curAdBuyCpmThreshold)
      {
         if (curAdBuyCpmThreshold < minSpotCpm)
         {
            //
            // Before marking bid as being beyond buy's reach, check
            // whether it is the only bidder & it could afford the rate card
            // discount.
            if (bidQualifiesForRateCardDiscount(bidder))
            {
               return true;
            }
            // 1. The spot's minimum price is greater than our buy's max.
            bidder.auctionState = AuctionStatus.BUY_CPM_LESS_THAN_SPOT_MIN;
            return false;
         }
         if (prevActualCpm > curAdBuyCpmThreshold
            && prev.auctionState == AuctionStatus.IN_PLAY)
         {
            // 2. Our previous (lower-ranked) bid was greater than this buy's
            // max offer,
            // and it is still in play (not disqualified for other
            // inadequacies).
            // Even though this bid has a higher rank, it can't afford to
            // continue
            // to be in the auction. Set actualCPM for logging purposes.
            bidder.setActualCPM(curAdBuyCpmThreshold, null);
            bidder.auctionState = AuctionStatus.CPM_EXCEED_THRESHOLD;
            return false;
         }
         else
         {
            // Adjust our bid's CPM down to the maximum we'd pay. Maintain
            // pricing type.
            bidder.setActualCPM(curAdBuyCpmThreshold, bidder.pricingType());
         }
      }
      return true;
   }

   /**
    * Check the automatic adjacency rules for this bid. Typically, only one
    * media buy is allowed to win once per break, but this may be overridden for
    * 15-second avails/buys.
    * 
    * @param bidder
    * @return true if adjacency passes.
    */
   private boolean autoAdjacencyPasses(AuctionObjectShadow bidder)
   {
      return myBudget.autoAjacencyPasses(bidder);
   }

   /**
    * @false if a creativeId is not found that can win, true otherwise. If true,
    *        also set the creativeId from the subset list of creativeIds for
    *        this bid that would be chosen as the next winner based on the
    *        rotation_type for this CB and the CreativeRotation stats on each
    *        creative in the list.
    */
   private boolean creativeRotationPasses(AuctionObjectShadow bid)
   {
      Collection<Integer> crIds = bid.getCreativeIds();

      // If no creatives exist on the bid, we'll use this state.
      AuctionStatus as = AuctionStatus.NO_BID_CREATIVE_ID;

      if (!crIds.isEmpty() && myForcePreviousWinCbCreative)
      {
         // General CR failure
         as = AuctionStatus.FAILED_CREATIVE_ROTATION;

         // If this bid was previously won by this CB, then we
         // must use the same creativeId that won previously as
         // long as it's still in the list.
         if (bid.isSameCampaignBuyWinner())
         {
            // If it's still exists in the list, we'll create a list
            // with this single creative id so it is chosen in
            // nextCreativeRotationWinner().
            Integer curId = new Integer(bid.previousCreativeIdWinner());
            if (crIds.contains(curId))
            {
               crIds = new ArrayList<Integer>(1);
               crIds.add(curId);

               // This will be the state set on the bid if
               // we can't find the previous creative winner in the CB.
               as = AuctionStatus.CREATIVE_ID_FROM_PREVIOUS_WIN_NOT_IN_LIST;
            }
         }
      }
      // Set the bid's creative.
      CampaignBuyAuctionInfo cb = bid.auctionObj.campaignBuy;
      int crId = cb.nextCreativeRotationWinner(crIds);
      bid.setSelectedCreativeId(crId);

      if (crId == AdAuctionAppConstants.UNKNOWN_ID_VALUE)
      {
         // We didn't find a creative.
         bid.auctionState = as;
         return false;
      }

      return true;
   }

   /**
    * Check the various adjacency rules for this bidder for this spot.
    * 
    * @param bidder
    * @return true if bid would qualify. If false, the bidder's status is set to
    *         the appropriate reason for failure.
    */
   private boolean advertiserAjacencyPasses(AuctionObjectShadow bidder)
   {
      return myBudget.advertiserAjacencyPasses(bidder, myCurToggles);
   }
   
   /**
    * Check for time-based proximity conflict on channel.
    * 
    * @param bidder
    * @return true if bid would qualify. If false, the bidder's status is set to
    *         the appropriate reason for failure.
    */
   private boolean proximityRestrictionPasses(AuctionObjectShadow bidder)
   {
      return myBudget.proximityRestrictionPasses(bidder);
   }

   /**
    * Check whether the current bid is within our budget constraints. This
    * includes overall campaign, buy weekly, and buy daily limits.
    * 
    * @param bidder
    * @return true if passes budget limits. If false, the bidder's status is set
    *         to the appropriate reason for failure.
    */
   private boolean budgetLimitsPass(AuctionObjectShadow bidder)
   {
      return myBudget.budgetLimitsPass(bidder, myCurToggles);
   }

   /**
    * Check whether there are other winners who have conflicting product
    * attributes in the bid's break.
    * 
    * @param bidder
    * @return true
    */
   private boolean productAdjacencyPasses(AuctionObjectShadow bidder)
   {
      return myBudget.productAttributesPass(bidder);
   }

   /********************************************************
    * WINNER PROCESSING SECTION
    ********************************************************/

   /**
    * Record this bidder as the winner of its target spot. The win type is set
    * to be normal. This method is NOT thread safe.
    * 
    * @param bidder Winning bidder
    * @param pass The AuctionPass we are currently running.
    */
   public void setAsWinner(AuctionObjectShadow bidder, AuctionPass pass)
   {
      setAsWinner(bidder, pass, WinType.NORMAL);
   }

   /**
    * Record this bidder as the winner of its target spot. This method is NOT
    * thread safe.
    * 
    * @param bidder Winning bidder
    * @param pass The AuctionPass we are currently running.
    * @param winType WinType (assigned, normal)
    */
   public void setAsWinner(
      AuctionObjectShadow bidder,
      AuctionPass pass,
      WinType winType)
   {
      // For admira2.1, this will also update the creative rotation
      // stats for the CB/creative
      bidder.setWinner(pass, winType);

      addToWinnerTotals(bidder);

      // Add some debug info if debugging is turned on.
      setAuctionDebugAction(bidder, true);
   }

   /**
    * Print out segment information for a winner, if segment debugging is set.
    * 
    * @param bid to print info on.
    * @param boolean isWinner
    */
   private void setAuctionDebugAction(AuctionObjectShadow bid, boolean isWinner)

   {
      if (isSegmentDebugOn())
      {
         myNumWinners++;

         Spot spot = bid.auctionObj.spot;
         String isSegmented = (spot.isSegmented() ? "*" : "-");
         String isMirrored = (spot.isMirrored() ? "m" : "-");
         Spot mirror = (spot.isMirrored() ? spot.mirroredPartner() : null);
         String avail =
            "Root=" + (spot.isSegmented() ? spot.segmentRootID : -1)
               + " Avail=" + spot.id + " Mirr="
               + (mirror == null ? "-1" : mirror.id);

         // Add a debug action for a winning bid.
         String bidAllows = (bid.auctionObj.allow2WinsPerBreak ? "*" : "-");
         String title = "Auction-Loser ";
         String bidAttrs = "";
         if (isWinner)
         {
            title = "Auction-Winner";
            bidAttrs = SegmentSet.getWinningBidCridAttrs(bid);
         }

         // m*Auction Winner#111: Buy#00322--Cr#00402(13) -1486200.00 Root=43758
         // Avail=43758 (201015/4) (30/0) (322:402-24,421-22,422-22) WINNER

         String msg =
            String
               .format(
                  "\t%s%s%s #%d: Buy#%05d%s-Cr#%05d(%s)\t%11.2f %10s %11s (%d/%d) (%02d/%02d) %s",
                  isMirrored, isSegmented, title, myNumWinners,
                  bid.auctionObj.campaignBuy.campaignBuyID, bidAllows, bid
                     .getSelectedCreativeId(), bidAttrs, bid.auctionObj.rank(),
                  bid.auctionCost().toString(), avail, spot.budgetWeekIndex,
                  spot.budgetDayOfWeek, spot.duration, spot.segmentOffset,
                  bid.auctionObj.campaignBuy.dumpCreativeRotation() + " "
                     + bid.auctionState);

         // Add this msg to the debug actions.
         mySegments.get(0).addDebugAction(msg, mySegmentDebugActions);
      }
   }

   private boolean isSegmentDebugOn()
   {
      return (myDoSegmentDebug && !mySegments.isEmpty());
   }

   private void debugSegmentSet(String msg)
   {
      if (isSegmentDebugOn())
      {
         // Add this msg to the debug actions.
         mySegments.get(0).addDebugAction(msg, mySegmentDebugActions);
      }
   }

   /**
    * Update budget and impression numbers, and program content tallies for a
    * winning bidder. This method is NOT thread safe.
    * 
    * @param winner Winning AuctionObjectShadow
    */
   private void addToWinnerTotals(AuctionObjectShadow winner)
   {
      logErrorIfHasConditionalTotals(winner);

      myBudget.addToWinnerBudgetTotals(winner);
      myBudget.addToWinnerContentTotals(winner, false);

      myLastWinners.add(winner);
      myStats.updateWithWinner(winner);
   }

   /**
    * Add a win conditionally. Used for conditional process for mirroring,
    * segments, etc. Add to budget and impression numbers for a winning bidder.
    * Add tallies to the appropriate break, daypart, program, bundling & product
    * attribute mappings. These bids should be removed with
    * unrollConditionalTotals() first before setting winners. This method is NOT
    * thread safe.
    * 
    * @param aos Bid to conditionally add.
    */
   public void addToConditionalTotals(AuctionObjectShadow aos)
   {
      if (aos == null)
         return;

      logErrorIfHasConditionalTotals(aos);

      myBudget.addToWinnerBudgetTotals(aos);
      String dbgMsg =
         myBudget.addToWinnerContentTotals(aos, isSegmentDebugOn());

      aos.setHasConditionalTotals(true);

      debugSegmentSet("   AddToTotals() " + dbgMsg);
   }

   /**
    * Unroll a conditional win. Used for conditional process for mirroring,
    * segments, etc. Unroll budget and impression numbers for a bidder. Unroll
    * tallies to the appropriate break, daypart, program, bundling & product
    * attribute mappings. This must be a bid that was added with
    * addToConditionalTotals(). This method is NOT thread safe.
    * 
    * @param aos Bid to conditionally remove.
    */
   public void unrollConditionalTotals(AuctionObjectShadow aos)
   {
      if (aos == null)
         return;

      // Log an error if this bid doesn't have conditional totals applied.
      logErrorIfNoConditionalTotals(aos);

      myBudget.unrollWinnerBudgetTotals(aos);
      String dbgMsg =
         myBudget.unrollWinnerContentTotals(aos, isSegmentDebugOn());

      aos.setHasConditionalTotals(false);

      debugSegmentSet("   UnrollTotals() " + dbgMsg);
   }

   /**
    * Check to make sure the given bid does not already have conditional totals
    * applied.
    */
   private void logErrorIfHasConditionalTotals(AuctionObjectShadow aos)
   {
      if (aos.hasConditionalTotals())
      {
         // If we get here, we have a bug
         // Only report as many as myMaxConditionalAddToErrors errors.
         if (myMaxConditionalAddToErrors > 0)
         {
            myMaxConditionalAddToErrors--;
            String msg =
               "logErrorIfHasConditionalTotals(): "
                  + "Adding to conditional totals on a bid "
                  + "that already had conditional totals applied." + " Bid: "
                  + aos + " " + getStackTrace();

            theLogger.error(msg);
            debugSegmentSet(msg);
         }
      }
   }

   /**
    * Check to make sure the given bid has conditional totals applied.
    */
   private void logErrorIfNoConditionalTotals(AuctionObjectShadow aos)
   {
      if (!aos.hasConditionalTotals())
      {
         // If we get here, we have a bug
         // Only report as many as myMaxConditionalUnrollErrors errors.
         if (myMaxConditionalUnrollErrors > 0)
         {
            myMaxConditionalUnrollErrors--;

            String msg =
               "logErrorIfNoConditionalTotals(): "
                  + "Unrolling conditional totals on a bid "
                  + "that didn't have conditional totals applied." + " Bid: "
                  + aos + " " + getStackTrace();

            theLogger.error(msg);
            debugSegmentSet(msg);
         }
      }
   }

   private String getStackTrace()
   {
      StringBuilder sb = new StringBuilder();
      StackTraceElement[] stack = Thread.currentThread().getStackTrace();

      if (stack != null && stack.length > 0)
      {
         sb.append(" *** ");
         for (int i = 0; i < stack.length; i++)
         {
            sb.append(stack[i].toString());
            sb.append(" *** ");
         }
      }
      return sb.toString();
   }

   /********************************************************
    * PRE-ASSIGN WINNER SECTION
    ********************************************************/

   /**
    * Handle any preassigned winners.
    * 
    * @param pass Auction Pass to mark these winners with.
    */
   private void handlePreassignedWinners(AuctionPass pass)
   {
      // Do not preassign winners in simulated auctions!
      if (!isRealAuction())
      {
         return;
      }
      for (PreassignedWinner preWin : myPreassignedWinners)
      {
         AuctionObjectShadow aos = findPreassignedAOS(preWin);
         if (aos == null)
         {
            logPreassignedError(preWin);
            continue;
         }
         setPreassignedWinner(aos, preWin, pass);

         // If segment root, invalidate other bids in the Segment.
         Spot spot = aos.auctionObj.spot;
         if (spot.isSegmented())
         {
            aos.auctionSpot.segmentSet.markAllSegmentNonWinners();
         }
      }
   }

   /**
    * Set an AuctionObjectShadow as a preassigned winner. Mark the assigned
    * winner as succeeding.
    * 
    * @param winner Bid to mark.
    * @param preWin Winner definition.
    * @param pass Auction Pass to mark this winner with.
    */
   private void setPreassignedWinner(
      AuctionObjectShadow winner,
      PreassignedWinner preWin,
      AuctionPass pass)
   {
      // Simulates side-effects of normal auction:
      winner.setActualCPM(preWin.assignedCPM(), PricingType.PREASSIGNED); // This
                                                                          // sets
                                                                          // bid
                                                                          // cost
                                                                          // so
                                                                          // we
                                                                          // can...
      winner.lastMinBidPrice = winner.auctionCost();
      winner.setSelectedCreativeId(preWin.creativeID());
      winner.isSeen = true;
      // Now set winner as usual.
      setAsWinner(winner, pass, WinType.ASSIGNED);
      // Mark the PreassignedWinner as SUCCESS.
      preWin.setAssignStatus(AssignStatus.SUCCESS);
   }

   /**
    * Given a PreassignedWinner definition, find the corresponding bid with the
    * same avail ID, buy ID, and creative ID. Also, ensure any other
    * preassigned-winner constraints are met. If a bid is not found, return
    * null, and mark the PreassignedWinner with the corresponding error. Note
    * any problem is NOT marked on the AOS bid object itself.
    * 
    * @param preWin Preassigned Winner definition.
    * @return AuctionObjectShadow or null if not found.
    */
   private AuctionObjectShadow findPreassignedAOS(PreassignedWinner preWin)
   {
      AuctionSpot as = myAuctionSpots.get(preWin.availID());
      if (as == null)
      {
         // Avail is not in our auction pool.
         preWin.setAssignStatus(AssignStatus.INVALID_AVAIL);
         return null;
      }

      if (as.winner != null)
      {
         // Avail has already won (duplicate avails assigned?)
         preWin.setAssignStatus(AssignStatus.INVALID_AVAIL);
         return null;
      }

      // Assigning a segmented (partial) avail winner is not supported.
      Spot s = as.spot;
      if (s.isSegmented() && s.id != s.segmentRootID)
      {
         preWin.setAssignStatus(AssignStatus.SEGMENTS_NOT_SUPPORTED);
         return null;
      }

      int buyID = preWin.buyID();
      int wcrID = preWin.creativeID();
      for (AuctionObjectShadow aos : as.bidders)
      {
         // Find the specified buy bidding on this spot.
         AuctionObject ao = aos.auctionObj;
         if (buyID == ao.campaignBuy.campaignBuyID)
         {
            // Check that, if the spot is mirrored, the bid has a mirror
            // partner.
            if (s.isMirrored())
            {
               if (aos.mirroredPartnerBid() == null)
               {
                  // Cannot assign this winner if mirror bid not found.
                  preWin
                     .setAssignStatus(AssignStatus.MIRROR_PARTNER_BID_NOT_FOUND);
                  return null;
               }
               // Check for matching assigned mirror bid.
               int mirrAvailID = aos.mirroredPartnerBid().auctionObj.spot.id;
               if (!preassignedBidExists(mirrAvailID, buyID, wcrID))
               {
                  // Cannot assign this winner if no matching bid was assigned.
                  preWin
                     .setAssignStatus(AssignStatus.MIRROR_PARTNER_NOT_ASSIGNED);
                  return null;
               }
            }
            // Find the creative used by this bid.
            for (Integer bidCr : ao.creativeIds)
            {
               if (bidCr == wcrID)
               {
                  // Check that creative has been approved.
                  if (!ao.campaignBuy.isCreativeApprovedByOrg(wcrID,
                     s.breakView.orgId))
                  {
                     // Cannot assign this winner if creative not approved.
                     preWin.setAssignStatus(AssignStatus.CREATIVE_NOT_APPROVED);
                     return null;
                  }
                  // Success!
                  return aos;
               }
            }
            // Found buy & avail, but the assigned Creative wasn't in this bid's
            // list.
            preWin.setAssignStatus(AssignStatus.INVALID_CREATIVE);
            return null;
         }
      }
      // Found the avail but no bid from this buy.
      preWin.setAssignStatus(AssignStatus.INVALID_BUY);
      return null;
   }

   /**
    * Check that a PreassignedWinner from our list exists. Used for mirrored
    * check rule.
    * 
    * @param availID
    * @param buyID
    * @param crID
    * @return true if one exists, false if not.
    */
   private boolean preassignedBidExists(int availID, int buyID, int crID)
   {
      for (PreassignedWinner pw : myPreassignedWinners)
      {
         if (pw.availID() == availID && pw.buyID() == buyID
            && pw.creativeID() == crID)
         {
            return true;
         }
      }
      return false;
   }

   /**
    * Log a preassignment problem (only if this is a real auction).
    * 
    * @param preWin Preassigned winner definition.
    */
   private void logPreassignedError(PreassignedWinner preWin)
   {
      if (isRealAuction())
      {
         String msg = "Preassign Winner failure: " + preWin.toString();
         theLogger.warn(msg);
         SystemAlert.alertAccountServices(msg);
      }
   }

   /********************************************************
    * SORTING / COMPARATORS SECTION
    ********************************************************/

   /**
    * Sort a list of AuctionObjectShadow objects by rank using a comparator.
    * 
    * @param bidders
    * @param comparator
    * @return
    */
   private List<AuctionObjectShadow> rankBidders(
      AuctionObjectShadow[] bidders,
      Comparator<AuctionObjectShadow> comparator)
   {
      List<AuctionObjectShadow> rtnList =
         new ArrayList<AuctionObjectShadow>(bidders.length);
      for (AuctionObjectShadow aos : bidders)
      {
         rtnList.add(aos);
      }
      Collections.sort(rtnList, comparator);
      return rtnList;
   }

   /** Comparator to order AuctionShadowObject by ascending rank */
   private static final Comparator<AuctionObjectShadow> RANK_ASCEND_COMPARATOR =
      new Comparator<AuctionObjectShadow>()
      {
         public int compare(AuctionObjectShadow aos1, AuctionObjectShadow aos2)
         {
            // Use same comparator as ranking winners only reverse polarity to get ascending rank
            return -(RANK_WINNER_DESC_COMPARATOR.compare(aos1, aos2));
         }
      };

   /** Comparator to order AuctionShadowObject by descending rank */
   public static final Comparator<AuctionObjectShadow> RANK_WINNER_DESC_COMPARATOR =
      new Comparator<AuctionObjectShadow>()
      {
         public int compare(AuctionObjectShadow aos1, AuctionObjectShadow aos2)
         {
            AuctionObject ao1 = aos1.auctionObj;
            AuctionObject ao2 = aos2.auctionObj;
            Spot spot1 = ao1.spot;
            Spot spot2 = ao2.spot;

            // First order by ascending week, so we auction one budget week at a
            // time.
            Integer dayIndex1 = spot1.budgetWeekIndex;
            Integer dayIndex2 = spot2.budgetWeekIndex;
            int rtn = dayIndex1.compareTo(dayIndex2);
            if (rtn != 0)
               return rtn;

            // Same week. Now order by descending rank.
            float r1 = ao1.rank();
            float r2 = ao2.rank();
            // Negative rank fix: if both bids have negative
            // rank values, use the alternate rank to compare.  Negative ranks
            // invert value comparisons.  However, any negatively-ranked
            // bid should be ranked lower than a zero or positive rank value.
            if (r1 < 0 && r2 < 0)
            {
                r1 = aos1.alternateRank;
                r2 = aos2.alternateRank;
            }
            rtn = Float.compare(r2, r1);
            if (rtn != 0)
               return rtn;

            // If rank is the same, order by buy's auction cost, descending.
            Money cost1 = aos1.auctionCost();
            Money cost2 = aos2.auctionCost();
            rtn = cost2.compareTo(cost1);
            if (rtn != 0)
               return rtn;
            
            // When this comparator is used for the pricing step, auction
            // costs will always be zero.  If costs are same, order by
            // descending derived CPM (on the buys) instead of cost.
            float cpm1 = ao1.derivedCPM();
            float cpm2 = ao2.derivedCPM();
            rtn = Float.compare(cpm2, cpm1);
            if (rtn != 0)
               return rtn;

            // If rank/cost are the same, order by segmentRootID,
            // segmentOffset & availId
            // so the results are the same each time we auction, and predictable
            // for test cases.
            rtn = (spot1.segmentRootID - spot2.segmentRootID);
            if (rtn != 0)
               return rtn;

            // Finally, order by segment offset (descending). This is a minor
            // efficiency
            // for 60-second Segmented Avails. Choosing the higher offset allows
            // us
            // to evaluate 15/15/30 Segment Combinations first (that is, the
            // 2nd 30-second partial). The pair of 15's will always overlap the
            // first
            // 30 in any 60-second segment, so we want to evaluate
            // the 1st 30 LAST (since, in the current implementation,
            // this 30 will have no matching pair of 15's that allow it to
            // be evaluated as part of a 15/15/30 combo).
            // 
            Integer segOff1 = spot1.segmentOffset;
            Integer segOff2 = spot2.segmentOffset;
            rtn = segOff2.compareTo(segOff1);
            if (rtn != 0)
               return rtn;

            // Same rank, price, & segment. Order by avail (ascending) ID for
            // next check.
            rtn = spot1.id - spot2.id;
            if (rtn != 0)
               return rtn;

            // These 2 bids have equal rank, from 2 buys bidding on the same
            // avail.
            // To avoid awarding the higher rank to the same buy every time (we
            // previously used the Campaign Buy ID), we compare hashes of
            // availID and buyIDs:
            // (availID ^ buyID)
            // This needs to be able to order 2 or more bids pseudo-randomly
            // on the same avail, regardless of the pool.
            int h1 = (spot1.id ^ ao1.campaignBuy.campaignBuyID);
            int h2 = (spot2.id ^ ao2.campaignBuy.campaignBuyID);
            rtn = (h1 ^ h2) & 1;
            // If hash of hashes is odd, order asc; if even, order desc.
            return ((rtn == 0) ? -1 : 1);
         }
      };

   /**************************************************************
    * PER-AUCTION STATISTICS & SUMMARIES
    **************************************************************/

   /**
    * Return a string representation of the last auction's statistics.
    * 
    * @return statistics about last auction.
    */
   public String dumpStatistics()
   {
      StringBuilder sb = new StringBuilder();
      if (isRealAuction())
      {
         sb.append("<SYSTEM AUCTION>\n");
      }
      else
      {
         sb.append("<SIMULATED AUCTION>\n");
         sb.append(dumpCBInfo(myCampaignBuyAuctionInfo));
         sb.append("\n");
         // Previous stats (double auction) only occur in simulated auctions.
         for (AuctionStats as : myPrevStatsList)
         {
            sb.append("<== Prep. Auction Stats ==>\n");
            sb.append(as.toString());
            sb.append("\n");
         }
      }
      sb.append("<== Auction Stats ==>\n");
      sb.append(myStats.toString());
      sb.append("\n");
      sb.append(dumpResultSummary());
      return sb.toString();
   }

   /**
    * @param cbi CampaignBuyAuctionInfo to describe.
    * @return Our CampaignBuyAuctionInfo data.
    */
   public String dumpCBInfo(CampaignBuyAuctionInfo cbi)
   {
      StringBuilder sb =
         new StringBuilder("CAMPAIGN BUY #" + cbi.campaignBuyID + " INFO:");
      if (cbi.mediaBuyType == MediaBuyType.SPENDING_LIMITED)
      {
         sb.append("\n\tSpending Limits: Daily: " + cbi.effectiveDailyRemainingSpendingLimit);
         sb.append(" / Overall Buy: " + cbi.effectiveSpendingLimit);
         sb.append(" / Campaign: " + cbi.effectiveCampaignSpendingLimit);
      }
      else
      {
         sb.append("\n\tImpression Limits: Daily: " + String.format("%,d", cbi.effectiveDailyRemainingImpressionLimit));
         sb.append(" / Overall Buy: " + String.format("%,d", cbi.effectiveImpressionLimit));
         sb.append(" / Campaign: " + String.format("%,d", cbi.effectiveCampaignImpressionLimit));
      }
      sb.append("\n\tTO-DATE SPENDING/IMPRESSIONS: Buy: " + cbi.currentSpending);
      sb.append("/" + cbi.currentImpressions);
      sb.append("   Campaign: " + cbi.currentCampaignSpending);
      sb.append("/" + cbi.currentCampaignImpressions);
      sb.append("\n\tCPM: Derived: " + toMoney(cbi.derivedCpm));
      sb.append(", Target: " + toMoney(cbi.targetCPM));
      sb.append("\n\tPost-Auction Baseline Eff: "
         + (int) cbi.baselineEfficiency + "%");
      return sb.toString();
   }

   /**
    * Show the win count, and counts for all the reasons that our AdBuy (or if
    * periodic auction, that all Ad Buys), lost in its bids in the last auction.
    * 
    * @return String of winners.
    */
   public String dumpResultSummary()
   {
      StringBuilder sb = new StringBuilder("\tRESULTS:");
      if (!myStats.auctionOccurred())
      {
         sb.append(" <no auction run>\n");
         return sb.toString();
      }
      if (lastAuctionBids().length == 0)
      {
         sb.append(" <no bids>\n");
         return sb.toString();
      }
      boolean isSystem = isRealAuction();
      int curWins = 0;
      int futWins = 0;
      Map<AuctionStatus, Integer> map = new HashMap<AuctionStatus, Integer>();
      for (AuctionObjectShadow aos : lastAuctionBids())
      {
         int buyId = aos.auctionObj.campaignBuy.campaignBuyID;
         if (isSystem || buyId == myCampaignBuyId)
         {
            AuctionStatus as = aos.auctionState;
            if (as == AuctionStatus.WINNER)
            {
               if (aos.auctionObj.spot.isFuture)
                  futWins++;
               else
                  curWins++;
               continue;
            }
            // Find the count for this losing status.
            Integer count = map.get(as);
            if (count == null)
            {
               count = 0;
            }
            count++;
            map.put(as, count);
         }
      }

      sb.append("\n\tWINS: ");
      sb.append(curWins + futWins);
      sb.append(" (" + curWins + " cur, ");
      sb.append(futWins + " future)");
      sb.append("\n\tLOSING REASON COUNTS:\n");
      for (AuctionStatus as : map.keySet())
      {
         int count = map.get(as);
         sb.append("\t  ");
         sb.append(as.name() + ": " + count);
         sb.append('\n');
      }
      return sb.toString();
   }

   /**
    * Dump the count of AuctionStatus types for all bids for this auction.
    * 
    * @return String of winners.
    */
   public String dumpAuctionStatusLists()
   {
      Map<AuctionStatus, Integer> statusMap =
         new HashMap<AuctionStatus, Integer>();
      for (AuctionObjectShadow aos : lastAuctionBids())
      {
         AuctionStatus as = aos.auctionState;
         Integer count = statusMap.get(as);
         if (count == null)
         {
            count = new Integer(0);
         }
         count++;
         statusMap.put(as, count);
      }
      StringBuilder sb = new StringBuilder("AUCTION BID STATUS COUNTS:\n");
      for (AuctionStatus statusType : statusMap.keySet())
      {
         sb.append("\t" + statusType.name());
         int count = statusMap.get(statusType);
         sb.append(": " + count);
         sb.append(" bids.\n");
      }
      return sb.toString();
   }

   /**
    * Print all current spending info.
    * 
    * @return String describing spending state.
    */
   public String dumpSpending()
   {
      StringBuilder sb = new StringBuilder();
      sb.append(myBudget.dumpSpending());
      return sb.toString();
   }

   /**
    * Print all spending info about the client buy only.
    * 
    * @return String describing spending state for the client buy of this
    *         auction.
    */
   public String dumpClientSpending()
   {
      StringBuilder sb = new StringBuilder();
      CampaignBuyAuctionTally buySpend = myBudget.clientTally();
      if (buySpend != null)
      {
         sb.append(createDelimiter("GRID BUY SPENDING"));
         sb.append(AuctionBudget.dumpCampaignBuySpending(buySpend));
      }
      return sb.toString();
   }

   /**
    * Dump the system parameters at the time of the last auction.
    * 
    * @return
    */
   public String dumpAuctionSystemParams()
   {
      return myAuctionSettings.toString();
   }

   /**
    * Dump the auction pass info.
    * 
    * @return String representation of pass list.
    */
   public String dumpAuctionPasses()
   {
      StringBuffer sb = new StringBuffer();
      for (AuctionPass pass : myPassList)
      {
         sb.append("[#" + pass.priority());
         sb.append("=" + pass.priorityAdjustPercent() + "%] ");
      }
      return sb.toString();
   }

   /**
    * Dump the status of all pre-assigned winners at the time of the last
    * auction.
    * 
    * @return Preassigned winner status strings.
    */
   public String dumpPreassignedWinnerResults()
   {
      if (myPreassignedWinners.size() == 0)
         return "(none)";
      StringBuffer sb = new StringBuffer();
      for (PreassignedWinner preWin : this.myPreassignedWinners)
      {
         sb.append(preWin.toString());
         sb.append("\n");
      }
      return sb.toString();
   }

   /**
    * Dump inventory info from the cache.
    * 
    * @return String representing inventory summary data.
    */
   public String dumpInventoryInfo()
   {
      StringBuilder sb = new StringBuilder();
      sb.append(InventoryTracker.fmtDebugInventoryHeader());
      sb.append("\n");
      InventoryTracker iTrk = myAuctionPool.getInventoryInfo();
      for (CampaignBuyAuctionInfo buy : myAuctionPool
         .getAllActiveCampaignBuys())
      {
         for (Integer orgID : iTrk.organizations())
         {
            InventoryTracker.MsoInfo mso = iTrk.getMso(orgID);
            sb.append(mso.fmtDebugInventoryRecord(buy));
            sb.append("\n");
         }
      }
      return sb.toString();
   }

   /**************************************************************
    * DUMPING BID DETAILS
    **************************************************************/

   /**
    * Dump the details of an Auction to a PrintStream, showing the results of
    * all bids, including spending. Results may be very large. If pass snapshots
    * is turned on, dump the results after each auction pass.
    * 
    * @param out PrintWriter to print to.
    */
   public void dumpDetails(PrintWriter out)
   {
      if (mySnapshotEachPass)
      {
         dumpSnapshots(out);
      }
      else
      {
         dumpAllBids(out, false);
      }

      out.println(createDelimiter("PREASSIGNED WINNERS"));
      out.println(dumpPreassignedWinnerResults());
      out.println(createDelimiter("AUCTION SPENDING"));
      out.println(dumpSpending());
      out.println(createDelimiter("AUCTION BUDGET CHART"));
      out.println(myBudget.dumpBudgetChart());
      out.println(createDelimiter("AUCTION SETTINGS"));
      out.println(dumpAuctionSystemParams());
      out.println(createDelimiter("AUCTION PASSES"));
      out.println(dumpAuctionPasses());
      out.println(createDelimiter("LOADED INVENTORY"));
      out.println(dumpInventoryInfo());
      out.println(createDelimiter("AUCTION TIME"));
      out.println(myStats.runDateStr());
      out.flush();
   }

   /**
    * Dump the results (after the last pass has run) of all bids, sorted by
    * index. Results may be very large.
    * 
    * @param out PrintWriter to print to.
    * @param winnersOnly If true, only dump winning bids; if false, dump all.
    */
   public void dumpAllBids(PrintWriter out, boolean winnersOnly)
   {
      Set<AuctionObjectShadow> bids = sortBidsByIndex(false, winnersOnly);
      dumpBids(out, bids);
   }

   /**
    * Dump the results (after the last pass has run) of only the bids for this
    * client (i.e., this grid's ad buy), sorted by index. Results may be very
    * large. Includes client spending info.
    * 
    * @param out PrintWriter to print to.
    * @param winnersOnly If true, only dump winning bids; if false, dump all.
    */
   public void dumpClientBids(PrintWriter out, boolean winnersOnly)
   {
      if (isRealAuction())
      {
         return;
      }
      Set<AuctionObjectShadow> bids = sortBidsByIndex(true, winnersOnly);
      dumpBids(out, bids);
      out.println(dumpClientSpending());
   }

   /**
    * Dump the results of a set of bids.
    * 
    * @param out PrintWriter to print to.
    * @param bids Bids to dump.
    */
   private void dumpBids(PrintWriter out, Set<AuctionObjectShadow> bids)
   {
      out.println(BidSnapshot.getDetailHeader());
      for (AuctionObjectShadow aos : bids)
      {
         BidSnapshot bid = createSnapshot(aos, null);
         out.println(bid.toDetailString());
      }
      out.flush();
   }
   
   /**
    * Format all bids with additional demographic data.
    * @param dc Demographic Cache
    * @param out Output
    * @param clientOnly If true, only include bids from this AuctionClient.
    */
   public void dumpBidsWithDemographics(DemographicCache dc, PrintWriter out, boolean gridOnly)
   {
       Set<AuctionObjectShadow> bids = sortBidsByIndex(gridOnly, false);
       
       // Print bid header, then demo header.
       out.print(BidSnapshot.getDetailHeader());
       out.println(dc.getDetailHeader());
       // For each bid, print detail, then demo data for the avail.
       for (AuctionObjectShadow aos : bids)
       {
          BidSnapshot bid = createSnapshot(aos, null);
          int chID = aos.auctionObj.spot.breakView.channelId;
          int minOfWk = aos.auctionObj.spot.minuteOfTheWeek();
          out.print(bid.toDetailString());
          out.println(dc.toDetail(chID, minOfWk));
       }
       out.println();
       // Append universal data after a blank BidSnapshot.
       out.print(BidSnapshot.EMPTY_RECORD.toDetailString());
       out.println(dc.getUniversalDataDetail());
       out.flush();
   }

   /**
    * Format the bids results as an HTML page.
    * 
    * @param out PrintWriter to write HTML to.
    * @param Set of bids to format
    */
   private void dumpHtmlBids(PrintWriter out, Set<AuctionObjectShadow> bids)
   {
      boolean printedHdr = false;
      boolean isShaded = false;
      out.println(AuctionHtmlUtils.HTML_SORTING_BEGIN);
      out.println(AuctionHtmlUtils.TABLE_START);
      for (AuctionObjectShadow aos : bids)
      {
         BidSnapshot bid = createSnapshot(aos, null);
         if (!printedHdr)
         {
            out.println(bid.getHtmlCategoryHeader());
            out.println(BidSnapshot.getHtmlHeader());
            out.println(AuctionHtmlUtils.TBL_BODY_START);
            printedHdr = true;
         }
         out.println(bid.getHtmlRow(isShaded));
         isShaded = !isShaded;
         out.flush();
      }
      out.println(AuctionHtmlUtils.TBL_BODY_END);
      out.println(AuctionHtmlUtils.TABLE_END);
      out.println(AuctionHtmlUtils.HTML_END);
      out.flush();
   }

   /**
    * Format all bids from this auction to results as an HTML page.
    * 
    * @param out PrintWriter to write HTML to.
    * @param winnersOnly If true, only dump winning bids; if false, dump all.
    */
   public void dumpAllHtmlBids(PrintWriter out, boolean winnersOnly)
   {
      Set<AuctionObjectShadow> bids = sortBidsByIndex(false, winnersOnly);
      dumpHtmlBids(out, bids);
   }

   /**
    * Format only AuctionClient bids from this auction to results as an HTML
    * page.
    * 
    * @param out PrintWriter to write HTML to.
    * @param winnersOnly If true, only dump winning bids; if false, dump all.
    */
   public void dumpClientHtmlBids(PrintWriter out, boolean winnersOnly)
   {
      Set<AuctionObjectShadow> bids = sortBidsByIndex(true, winnersOnly);
      dumpHtmlBids(out, bids);
   }

   /**
    * Sort the bids by index.
    * 
    * @param clientOnly If true, only include bids from this AuctionClient.
    * @param winnersOnly If true, only dump winning bids; if false, dump all.
    * @return Set of bids where iterator gets bids in order of index.
    */
   public Set<AuctionObjectShadow> sortBidsByIndex(
      boolean clientOnly,
      boolean winnersOnly)
   {
      // Sort by bid index
      TreeSet<AuctionObjectShadow> sortedMap =
         new TreeSet<AuctionObjectShadow>(new Comparator<AuctionObjectShadow>()
         {
            public int compare(
               AuctionObjectShadow bid1,
               AuctionObjectShadow bid2)
            {
               Integer i1 = bid1.biddingIndex;
               Integer i2 = bid2.biddingIndex;
               return i1.compareTo(i2);
            }
         });
      int buyID = myCampaignBuyAuctionInfo.campaignBuyID;
      for (AuctionObjectShadow bid : lastAuctionBids())
      {
         if (clientOnly)
         {
            if (bid.auctionObj.campaignBuy.campaignBuyID != buyID)
            {
               continue;
            }
         }
         if (winnersOnly)
         {
            if (bid.auctionState != AuctionStatus.WINNER)
            {
               continue;
            }
         }
         sortedMap.add(bid);
      }
      return sortedMap;
   }

   /**
    * Show for each ad buy the list of winners.
    * 
    * @return String of winners.
    */
   public String dumpWinners()
   {
      Map<Integer, List<Integer>> spotListByAdBuy =
         new HashMap<Integer, List<Integer>>();
      for (AuctionObjectShadow aos : myLastWinners)
      {
         int buyId = aos.auctionObj.campaignBuy.campaignBuyID;
         int spotId = aos.auctionSpot.spot.id;
         List<Integer> adbuyList = spotListByAdBuy.get(buyId);
         if (adbuyList == null)
         {
            adbuyList = new LinkedList<Integer>();
            spotListByAdBuy.put(buyId, adbuyList);
         }
         adbuyList.add(spotId);
      }
      StringBuilder sb = new StringBuilder("AUCTION WINNERS:\n");
      for (Integer buyId : spotListByAdBuy.keySet())
      {
         sb.append("AdBuy #");
         sb.append(buyId + ": Spot");
         List<Integer> sList = spotListByAdBuy.get(buyId);
         Collections.sort(sList);
         for (Integer spotId : sList)
         {
            sb.append(" ");
            sb.append(spotId);
         }
         sb.append("\n");
      }
      return sb.toString();
   }

   /**************************************************************
    * SNAPSHOTS
    **************************************************************/

   /**
    * Clear the list of snapshots on this auctioneer. Thread-safe.
    */
   public void clearSnapshots()
   {
      synchronized (mySnapshots)
      {
         mySnapshots.clear();
      }
   }

   /**
    * Add a list of bid snapshots to our list. Thread-safe.
    * 
    * @param bidList List to add for a single pass.
    */
   public void addToSnapshots(List<BidSnapshot> bidList)
   {
      synchronized (mySnapshots)
      {
         mySnapshots.add(bidList);
      }
   }

   /**
    * Create a bid snapshot from the state of a bid during an AuctionPass.
    * 
    * @param bid
    * @param pri
    * @return
    */
   public static BidSnapshot createSnapshot(
      AuctionObjectShadow bid,
      AuctionPass pri)
   {
      AuctionObject ao = bid.auctionObj;
      Spot spot = ao.spot;
      CampaignBuyAuctionInfo buy = ao.campaignBuy;
      AuctionStatus status = bid.auctionState;
      if (!ao.isInProgram())
      {
         status = AuctionStatus.NOT_IN_PROGRAM;
      }
      // Only display priority disqualify if we are printing each pass.
      if (pri != null && !bid.isCurPriorityQualified())
      {
         status = AuctionStatus.PRIORITY_DISQUALIFIED;
      }
      // TODO: when or how is local time loaded using a local timezone? Using a utc calendar works for how
      // we currently load this.
      Calendar fDateCal = TimeUtils.utcCalendar();
      fDateCal.setTime(spot.fileDate);
      SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss");
      String localTime = formatter.format(spot.schedTimeLocal);
      
      Money effectiveWeeklySpendingLimit = buy.effectiveWeeklyRemainingSpendingLimits.get(spot.budgetWeekIndex);
      if (effectiveWeeklySpendingLimit == null)
         effectiveWeeklySpendingLimit = Money.ZERO;
      Long effectiveWeeklyImpressionLimit = buy.effectiveWeeklyRemainingImpressionLimits.get(spot.budgetWeekIndex);
      if (effectiveWeeklyImpressionLimit == null)
         effectiveWeeklyImpressionLimit = 0L;
      
      // Note: this is a Soap object. We pass mostlly primitives so we don't have to serialize a lot
      // of additional classes.
      return new BidSnapshot(
         buy.mediaBuyType == MediaBuyType.IMPRESSION_LIMITED,
         bid.biddingIndex, 
         buy.campaignBuyID,
         buy.auctionPriority, 
         spot.id, 
         GridUtils.formatShortDate(fDateCal),
         localTime,
         spot.budgetDayOfWeek,
         spot.minuteOfTheWeek(),
         spot.daypartID, 
         spot.breakView.channelId,
         spot.breakView.id, 
         spot.duration, 
         spot.segmentOffset, 
         bid.getSelectedCreativeId(), 
         spot.segmentRootID, 
         spot.mirroredAvailID,
         (pri == null ? null : pri.priority()),
         (bid.winPriority() == null ? null : bid.winPriority().priority()),
         (bid.winType() == null ? null : bid.winType().name()), 
         (bid.pricingType() == null ? null : bid.pricingType().abbrev()),
         spot.qualityType().toString(),
         spot.isFuture, 
         buy.baselineEfficiency, 
         ao.spotEfficiency(), 
         ao.efficiencySpread(), 
         ao.rank(),
         bid.alternateRank,
         ao.demographicAudienceBidEfficiency,
         spot.grp(),
         spot.policyPrice, 
         spot.commissionPercent(), 
         spot.floorPercent(), 
         spot.minimumPrice(),
         bid.lastMinBidPrice, 
         bid.auctionCost(),
         effectiveWeeklySpendingLimit, 
         buy.effectiveDailyRemainingSpendingLimit,
         effectiveWeeklyImpressionLimit, 
         buy.effectiveDailyRemainingImpressionLimit,
         buy.targetCPM, 
         buy.derivedCpm, 
         bid.actualCPM(), 
         spot.totalADViews, 
         ao.targetViews(), 
         status);
   }

   /**
    * Dump all the bid snapshots we have accumulated to a writer.
    * 
    * @param out PrintWriter to write to.
    */
   public void dumpSnapshots(PrintWriter out)
   {
      boolean printedHdr = false;
      synchronized (mySnapshots)
      {
         for (List<BidSnapshot> pass : mySnapshots)
         {
            for (BidSnapshot bid : pass)
            {
               if (!printedHdr)
               {
                  out.println(BidSnapshot.getDetailHeader());
                  printedHdr = true;
               }
               out.println(bid.toDetailString());
            }
         }
      }
      out.flush();
   }

   /**
    * Dump the snapshot of all bids for a specific priority pass to our List of
    * Snapshot Lists.
    * 
    * @param pass Auction priority pass this snapshot represents.
    */
   public void snapshotAllBids(AuctionPass pass)
   {
      // If this feature is off, don't use the memory.
      if (!mySnapshotEachPass)
         return;

      List<BidSnapshot> bidList = new ArrayList<BidSnapshot>();
      for (Integer id : myAuctionSpots.keySet())
      {
         AuctionSpot as = myAuctionSpots.get(id);
         // Filter out all bids on avails that had a winner in previous passes,
         // to reduce redundant (non-changing) win & has_winner entries.
         if (as.winner != null
            && pass.priority() > as.winner.winPriority().priority())
         {
            continue;
         }
         for (AuctionObjectShadow aos : as.bidders)
         {
            BidSnapshot bid = createSnapshot(aos, pass);
            bidList.add(bid);
         }
      }
      addToSnapshots(bidList);
   }

   /**************************************************************
    * SEGMENT STATISTICS
    **************************************************************/

   /**
    * Print out all bid results in an auction.
    * 
    * @param auctioneer
    * @param preLabel
    */
   private void printWinCounts(PrintWriter out)
   {
      // Print results
      int winCount30 = 0;
      int winCount60 = 0;
      int winCount15 = 0;
      int winCount = 0;
      HashSet<Integer> availWinSet = new HashSet<Integer>();
      for (AuctionObjectShadow bid : this.lastAuctionBids())
      {
         if (bid.auctionState == AuctionStatus.WINNER)
         {
            switch (bid.auctionObj.spot.duration)
            {
               case 60:
                  winCount60++;
                  break;
               case 15:
                  winCount15++;
                  break;
               case 30:
                  winCount30++;
                  break;
            }
            winCount++;
            int spotID = bid.auctionObj.spot.id;
            if (availWinSet.contains(spotID))
            {
               out.println("Double sold avail " + spotID);
            }
            availWinSet.add(spotID);
         }
      }
      out.println("WINNING BIDS: " + winCount + " out of "
         + this.lastAuctionBids().length);
      out.println("   " + winCount60 + " 60s, " + winCount30 + " 30s, "
         + winCount15 + " 15s.");
   }

   /**
    * Dump the Segmented Avail Stats that were collected on the last auction.
    * 
    * @param out PrintWriter to write to.
    */
   public void dumpSegmentedAvailStats(PrintWriter out)
   {
      if (mySegmentStats == null)
         return;

      SegmentStats stats = mySegmentStats;

      // buys = pool.getAllActiveCampaignBuys();
      List<CampaignBuyAuctionInfo> buys =
         this.auctionPool().getAllActiveCampaignBuys();
      int buyCount = buys.size();
      int spotCount = this.auctionSpotCount();

      printWinCounts(out);
      out.println("For " + buyCount + " buys and " + spotCount
         + " spots we built " + stats.SegmentCounter + " Segments, "
         + stats.ComboSetCounter + " ComboSets, " + stats.ComboCounter
         + " Combos");
      if (stats.SegmentCounter != 0)
      {
         double billion = 1000000000;
         double million = 1000000;

         String ranksum =
            " "
               + ((stats.TotalWinningRankSum > billion ? (stats.TotalWinningRankSum / billion)
                  + " Billion"
                  : (stats.TotalWinningRankSum > million ? (stats.TotalWinningRankSum / million)
                     + " Million"
                     : stats.TotalWinningRankSum)));
         out.println("  " + stats.IndxBidCounter + " indexed Bids.");
         out.println("  Top-Ranked evaluated " + stats.RankEvalCount
            + " times.");
         out.println("  Combinations evaluated to be winnable: "
            + stats.ComboEvalCount + " times.");
         out.println("     average per winning candidate: "
            + (stats.ComboSetCounter > 0 ? stats.RankEvalCount
               / stats.ComboSetCounter : 0));
         out.println("  canBeWinner() failed " + stats.HitLoser + " times.");
         out.println(" Evaluated "
            + stats.SegmentsEvaluated
            + " segments in "
            + stats.SegmentEvalMillis
            + "ms. ("
            + (stats.SegmentsEvaluated > 0 ? stats.SegmentEvalMillis
               / stats.SegmentsEvaluated : 0) + "ms per eval).");
         out.println(" PostAuction evaluated " + stats.NumPostAuctionEvaluated
            + " segments in " + stats.PostAuctionMillis + "ms.  #Winners="
            + stats.NumPostAuctionWinners);

         int segWins = 0;
         int partialWins = 0;
         for (SegmentSet ss : segmentSets())
         {
            if (ss.foundAllWinners())
            {
               segWins++;
            }
            else if (ss.foundWinner())
            {
               segWins++;
               partialWins++;
            }
         }
         double avgRank = (stats.TotalWinningRankSum / (double) (segWins));
         String avgRankStr =
            " "
               + (avgRank > billion ? (avgRank / billion) + " Billion"
                  : (avgRank > million ? (avgRank / million) + " Million"
                     : avgRank));

         out.println(" TOTAL RANK SUM:" + ranksum);
         out.println(" AVG rank per winning segment:" + avgRankStr);
         out.println("Segments Won: " + segWins + " (#PostAuctionWinners="
            + stats.NumPostAuctionWinners + ")");
         out.println("   60 Combos Won      : " + stats.Root60ComboWins
            + " (#PostAuction=" + stats.PostAuctionRoot60Wins + ")");
         out.println("   30-30 Combos Won   : " + stats.Pair30ComboWins
            + " (#PostAuction=" + stats.PostAuctionPair30Wins + ")");
         out.println("   30-15-15 Combos Won: " + stats.QuadComboWins
            + " (#PostAuction=" + stats.PostAuctionQuadWins + ")");
         out.println();
         out.println("   30 Combos Won      : " + stats.Root30ComboWins
            + " (#PostAuction=" + stats.PostAuctionRoot30Wins + ")");
         out.println("   15-15 Combos Won   : " + stats.Pair15ComboWins
            + " (#PostAuction=" + stats.PostAuctionPair15Wins + ")");
         out.println("   Seg. Partials Won  : " + partialWins);
         out.println();
         // System.out.println("  Gave win to root bid " + stats.RootWon +
         // " times.");
         // System.out.println("  Got to end of Combo " + stats.ReachedComboEnd
         // + " times.");
      }
      int c30 = 0;
      int c15 = 0;
      int c60 = 0;
      for (CampaignBuyAuctionInfo buy : buys)
      {
         switch (buy.creativeDuration)
         {
            case 30:
               c30++;
               break;
            case 60:
               c60++;
               break;
            case 15:
               c15++;
               break;
            default:
               out.println("Weird duration " + buy.creativeDuration + " sec.!");
         }
      }
      out.println(" Buy Durations: " + c60 + " 60s, " + c30 + " 30s, and "
         + c15 + " 15s");
      // out.println("SPENDING: \n" + auctioneer.dumpSpending());
      out.println("TIMING: \n" + this.dumpTimings());

      out.flush();
   }

   /**
    * Dump all the Segmented Avail Actions that occurred on the last auction.
    * 
    * @param out PrintWriter to write to.
    */
   public void dumpSegmentedAvailActions(PrintWriter out)
   {
      dumpSegmentedAvailStats(out);

      if (!myDoSegmentDebug)
         return;

      List<SegmentSet.DebugAction> tmpList = copySegmentDebugList();
      SegmentSet.dumpDebugActions(tmpList, out);
      out.flush();
   }

   /**
    * Copy the current SegmentDebugAction list into a new list and return it.
    * Synchronizes creating & dumping of entire list.
    * 
    * @return List of DebugAction objects.
    */
   private synchronized List<SegmentSet.DebugAction> copySegmentDebugList()
   {
      List<SegmentSet.DebugAction> tmpList =
         new ArrayList<SegmentSet.DebugAction>();
      tmpList.addAll(mySegmentDebugActions);
      return tmpList;
   }

   /**
    * Create a new SegmentDebugAction list. Synchronizes creating & dumping of
    * entire list.
    */
   private synchronized void createSegmentDebugList()
   {
      mySegmentDebugActions.clear();
   }

   /**
    * @return the SegmentStats for the last auction.
    */
   public SegmentStats getSegmentStats()
   {
      return mySegmentStats;
   }

   /**************************************************************
    * AUCTION STATS
    **************************************************************/

   /**
    * Print timings for last auction.
    * 
    * @return String describing spending state.
    */
   public String dumpTimings()
   {
      return myStats.timings();
   }

   /**
    * Allocate a new AuctionStats object for this Auctioneer.
    * 
    * @param aType AuctionType, which determines whether or not current stats
    *        are saved in prevStats. Has side effect of setting current
    *        AuctionType.
    */
   private void resetStats(AuctionType aType)
   {
      switch (aType)
      {
         case GREENFIELD:
         case SINGLE:
            myPrevStatsList.clear();
            break;
         case BASELINE:
            myPrevStatsList.add(myStats);
      }

      myStatsID = myAuctionCounter.incrementAndGet();
      myStats = new AuctionStats(myStatsID, myClient);
      myCurAuctionType = aType;
   }

   /**
    * Start timing this auction.
    */
   private void startStatsTiming()
   {
      myStats.startAuctionTiming();
   }

   /**
    * End timing this auction.
    */
   private void endStatsTiming()
   {
      myStats.endAuctionTiming();
   }

   /**
    * Format a float value to display as dollars.cents.
    * 
    * @param val Money value
    * @return Formatted string.
    */
   private static String toMoney(float val)
   {
      return String.format("$%,.2f", val);
   }

   /**
    * Create a section delimiter string
    * 
    * @param label to append to delimiter header.
    * @return Delimit header (including CRs).
    */
   private String createDelimiter(String label)
   {
      return DUMP_SECTION_DELIM + label + ":";
   }

   /******************** Class members **************************/
   /**
    * A CampaignBuyAuctionInfo representing NO AuctionClient.
    */
   public static final CampaignBuyAuctionInfo NO_CAMPAIGN_BUY =
      new CampaignBuyAuctionInfo();
   public static final CampaignBuyAuctionTally NO_CAMPAIGN_BUY_SPENDING =
      new CampaignBuyAuctionTally(NO_CAMPAIGN_BUY);

   private static final String NO_AUCTION_YET_MSG = "(No Auction Yet)";
   private static final String DUMP_SECTION_DELIM = "==== ";

   /******************** Instance members **************************/
   // Invariants
   private final AuctionClient myClient;
   private final AuctionPoolProvider myAuctionPool;
   private final int myCampaignBuyId;
   private final List<AuctionPass> myPassList;
   private final AuctionBudget myBudget;

   // Grid Configuration
   private AuctionViewToggles myCurToggles = null;
   private CampaignBuyAuctionInfo myCampaignBuyAuctionInfo = null;
   // Sys params
   private final AuctionSettings myAuctionSettings;
   private final float myAuctionWinMargin;
   private final int myEfficiencyThrPct;
   private final int myCpmThreshold;
   private final int myRemainImprLowerThreshPct;
   private final Map<Integer, Integer> myRateCardDiscountsByOrg;

   private final boolean myForcePreviousWinCbCreative;
   private boolean mySnapshotEachPass = false;
   private List<SegmentSet.DebugAction> mySegmentDebugActions =
      new ArrayList<SegmentSet.DebugAction>();
   private boolean myDoSegmentDebug = false;
   private SegmentStats mySegmentStats = null;

   // Collections
   private Map<Integer, AuctionSpot> myAuctionSpots =
      new HashMap<Integer, AuctionSpot>();
   private AuctionObjectShadow[] myAllAuctionObjects =
      new AuctionObjectShadow[0];
   private GridAuctionObject[] myClientAuctionObjects =
      new GridAuctionObject[0];
   private HashSet<Integer> myCompetitorsByBuyId = new HashSet<Integer>();
   private final HashSet<Integer> myUsedPriorities = new HashSet<Integer>();
   private final List<List<BidSnapshot>> mySnapshots =
      new ArrayList<List<BidSnapshot>>();
   private final List<SegmentSet> mySegments;
   private final List<PreassignedWinner> myPreassignedWinners =
      new ArrayList<PreassignedWinner>();

   // Stats
   private static AtomicInteger myAuctionCounter = new AtomicInteger(1);
   private AuctionStats myStats = null;
   private int myStatsID = -1;
   private List<AuctionStats> myPrevStatsList = new ArrayList<AuctionStats>();
   private int myNumWinners = 0;
   private AuctionType myCurAuctionType = AuctionType.SINGLE;

   // Results
   private List<AuctionObjectShadow> myLastWinners =
      new LinkedList<AuctionObjectShadow>();
   private Money myLastAuctionCost = Money.ZERO;
   private long myLastAuctionImpr = 0L;
   private float myLastAuctionCpm = 0f;
   private int myLastAuctionEff = -1;
   private CampaignBuyAuctionTally myLastAuctionClientSpending = null;
   private AuctionObjectShadow[] myLastAuctionBids = new AuctionObjectShadow[0];

   private static final int MAX_CONDITIONAL_ERRORS_PER_AUCTION = 10;
   private int myMaxConditionalAddToErrors = MAX_CONDITIONAL_ERRORS_PER_AUCTION;
   private int myMaxConditionalUnrollErrors =
      MAX_CONDITIONAL_ERRORS_PER_AUCTION;

   private static Logger theLogger = Logger.getLogger(Auctioneer.class);

   /**
    * Statistics about an auction.
    */
   private class AuctionStats
   {
      /**
       * Constructor
       * 
       * @param id Run number.
       * @parm client Grid info about the buy.
       */
      public AuctionStats(int id, AuctionClient client)
      {
         myAuctioneer = Auctioneer.this;
         myClient = client;
         myAdBuyId = campaignBuyId();
         myIsSystem = isRealAuction();
         if (myIsSystem)
         {
            myName = "SYSTEM" + "." + id;
         }
         else
         {
            myName = "AdBuy#" + myAdBuyId + "." + id;
         }
         myTimer = new Timestamper(myName);
         myGMTRunDate = TimeUtils.utcCalendar();
      }

      /**
       * Name of this auction instance.
       * 
       * @return
       */
      public String name()
      {
         return myName;
      }

      /**
       * Start the timing of this auction.
       */
      public void startAuctionTiming()
      {
         myDateRange = new DateRange(myClient.auctionDateRange());
         CampaignBuyAuctionInfo buyInfo = myClient.auctionInfo(); // may be
                                                                  // null.
         myAdBuyPreAuctionBEff =
            (buyInfo == null ? 0 : (int) buyInfo.baselineEfficiency);
         myAuctionType = myAuctioneer.myCurAuctionType;
         myTimer.start();
      }

      /**
       * Set a timestamp for an event.
       * 
       * @param name Name of event.
       */
      public void timestamp(String name)
      {
         myTimer.timestamp(name);
      }

      /**
       * End the timing of this auction.
       */
      public void endAuctionTiming()
      {
         myTimer.end();
         myCompletedAuction = true;
      }

      public String runDateStr()
      {
         return "Auction Run Date: " + GridUtils.formatShortDate(myGMTRunDate);
      }

      /**
       * Record the toggles for this auction.
       * 
       * @param t
       */
      public void setToggles(AuctionViewToggles t)
      {
         myToggles = t;
      }

      /**
       * @return true if the auction this Stats is recording acutally ran.
       */
      public boolean auctionOccurred()
      {
         return myCompletedAuction;
      }

      /**
       * Cost per thousand views in dollars for client of last auction.
       * 
       * @return Dollars per thousand views of last auction.
       */
      public float adbuyCpm()
      {
         return AuctionUtils.calculateCPM(myAdBuyViewersWon, myAdBuySales);
      }

      /**
       * @return Efficiency of last auction.
       */
      public int adbuyEfficency()
      {
         return GridUtils.calculateEfficiency(myAdBuyDigitalViewersWon,
            myAdBuyTargetViewersWon);
      }

      /**
       * @return Client's baseline efficiency before the auction starts.
       */
      public int preAuctionEfficiency()
      {
         return myAdBuyPreAuctionBEff;
      }

      /**
       * Update our statistics with a winner.
       * 
       * @param winner winning bidder.
       */
      public void updateWithWinner(AuctionObjectShadow winner)
      {
         // If my AdBuy won this, update my totals
         AuctionObject ao = winner.auctionObj;
         Spot spot = winner.auctionSpot.spot;
         if (ao.campaignBuy.campaignBuyID == myAdBuyId)
         {
            myWinCount++;
            myAdBuySales = myAdBuySales.plus(winner.auctionCost());
            myAdBuyViewersWon += spot.totalADViews;
            myAdBuyDigitalViewersWon += spot.totalDigitalViews;
            myAdBuyTargetViewersWon += ao.targetViews();
            if (winner.auctionCost().isZero())
            {
               myFreebieCount++;
            }
         }
         myTotalSales = myTotalSales.plus(winner.auctionCost());
         Money profit = winner.auctionCost().minus(spot.policyPrice);
         myProfitTotal = myProfitTotal.plus(profit);
         if (winner.isDiscount())
         {
            myDiscountCount++;
         }
      }

      /**
       * @return String version of statistics.
       */
      public String toString()
      {
         return toString(false);
      }

      /**
       * @param showTiming If true, display auction timings.
       * @return String version of statistics.
       */
      public String toString(boolean showTiming)
      {
         if (!auctionOccurred())
         {
            return NO_AUCTION_YET_MSG;
         }
         StringBuilder sb = new StringBuilder();
         sb.append(name() + " [");
         sb.append(myAuctionType.name() + "]:");
         sb.append("\n\t" + runDateStr());
         Calendar start = myDateRange.startDate();
         Calendar end = myDateRange.endDate();
         sb.append("\n\tAuction Date Range: "
            + GridUtils.formatShortDate(start));
         sb.append(" to " + GridUtils.formatShortDate(end));
         sb.append("\n\tToggles: " + myToggles.toString());
         sb.append("\n\tTotal Spots/Buys in auction pool: "
            + myAuctioneer.auctionSpotCount() + "/"
            + myAuctioneer.campaignBuyCompetitorCount());
         sb.append("\n\tTotal Bids: " + myAuctioneer.lastAuctionBids().length);
         sb.append("\n\tTotal spent in this auction: " + myTotalSales);
         sb.append("\n\tTotal profit above Rate Card: " + myProfitTotal);
         if (!isRealAuction())
         {
            sb.append("\n\tAdbuy Spots/Views won: ");
            sb.append(myWinCount + " spots / ");
            sb.append(String.format("%,d", myAdBuyViewersWon)
               + " total views / ");
            sb.append(String.format("%,d", myAdBuyTargetViewersWon)
               + " target views.");
            sb.append("\n\tAdBuy Bids: " + myAuctioneer.gridBiddersCount());
            sb.append("\n\tAdBuy Spending: " + myAdBuySales);
            sb.append(", CPM: " + toMoney(adbuyCpm()));
            sb.append("\n\tBuy Baseline Eff: " + preAuctionEfficiency());
            sb.append("%, Resulting Win Eff: " + adbuyEfficency() + "%");
            if (myFreebieCount > 0)
            {
               sb.append("\n\tSpots won by this AdBuy for FREE: "
                  + myFreebieCount);
            }
         }
         if (myDiscountCount > 0)
         {
            sb.append("\n\tSpots won at discount rate: " + myDiscountCount);
         }
         if (showTiming)
         {
            sb.append("\n" + timings());
         }
         return sb.toString();
      }

      /**
       * @return Timing information for auction.
       */
      public String timings()
      {
         return myTimer.toString();
      }

      /******** OBJECT MEMBERS *********/
      private final int myAdBuyId;
      private final String myName;
      private final AuctionClient myClient;
      private DateRange myDateRange;
      private final Auctioneer myAuctioneer;
      private Timestamper myTimer;
      private final Calendar myGMTRunDate;
      private AuctionViewToggles myToggles = null;
      private Money myTotalSales = Money.ZERO;
      private Money myAdBuySales = Money.ZERO;
      private int myAdBuyViewersWon = 0;
      private long myAdBuyDigitalViewersWon = 0;
      private long myAdBuyTargetViewersWon = 0;
      private Money myProfitTotal = Money.ZERO;
      private int myAdBuyPreAuctionBEff = 0;
      private int myWinCount = 0;
      private int myFreebieCount = 0;
      private int myDiscountCount = 0;
      private boolean myCompletedAuction = false;
      private boolean myIsSystem = false;
      private AuctionType myAuctionType = AuctionType.SINGLE;

      /** count of zero-viewer spots */
      public int zeroViewerSpots = 0;

   } // END AuctionStats

} // END Auctioneer

/**
 * Class that tracks timings for a series of steps, allowing the user to set a
 * timestamp at any point and print out results at the end. Nanosecond
 * granularity.
 */
class Timestamper
{
   /**
    * Constructor
    * 
    * @param n Name displayed on printout
    */
   public Timestamper(String n)
   {
      myName = n;
      myTimestamps = new ArrayList<TimeStamp>();
   }

   /**
    * Start timings.
    */
   public void start()
   {
      myStartTime = System.nanoTime();
      myTimestamps.add(new TimeStamp(START, myStartTime));
   }

   /**
    * Set a timestamp on this
    * 
    * @param name
    */
   public void timestamp(String name)
   {
      myTimestamps.add(new TimeStamp(name, System.nanoTime()));
   }

   /**
    * End timings.
    */
   public void end()
   {
      timestamp(END);
   }

   /**
    * Print out timings.
    */
   public String toString()
   {
      StringBuilder sb = new StringBuilder(myName + " Timings:\n");
      long prevTime = myStartTime;
      boolean isFirst = true;
      for (TimeStamp ts : myTimestamps)
      {
         if (!isFirst)
         {
            long fromStart = ts.timeNs - myStartTime;
            long fromStartMs = fromStart / TICS_PER_MS;
            long sinceLastMs = (ts.timeNs - prevTime) / TICS_PER_MS;
            sb.append("\t" + ts.name + ": ");
            sb.append(sinceLastMs);
            sb.append(" ms. [");
            sb.append(fromStartMs);
            sb.append(" ms. elapsed]\n");
         }
         prevTime = ts.timeNs;
         isFirst = false;
      }
      return sb.toString();
   }

   private static final String START = "INIT";
   private static final String END = "TOTAL";
   private static final int TICS_PER_MS = 1000000;

   private final String myName;
   private final List<TimeStamp> myTimestamps;

   private long myStartTime = 0;

   /**
    * Timing helper class.
    */
   private static class TimeStamp
   {
      /**
       * Constructor
       * 
       * @param n Event name
       * @param startTime
       */
      TimeStamp(String n, long startTime)
      {
         name = n;
         timeNs = startTime;
      }

      public final long timeNs;
      public final String name;
   } // END TimeStamp

} // END Timestamper
