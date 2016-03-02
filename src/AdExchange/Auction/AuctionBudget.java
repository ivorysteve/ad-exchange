/**
 * Part of a source code package originally written for the Navic AdExchange project.
 * Intended for use as a programming work sample file only.  Not for distribution.
 **/
package AdExchange.Auction;

import java.util.Calendar;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import util.TimeUtils;

import AdExchange.AdBuyGrid.Auction.AuctionViewToggles;
import AdExchange.AdvertisingAccount.AdvertisingAccountConstants.MediaBuyType;
import AdExchange.Auction.CampaignBuyAuctionTally.SpotProximityData;
import AdExchange.Cache.CacheConstants.DemographicQualityType;
import AdExchange.Cache.Central.AuctionAdjacencyRules;
import AdExchange.Cache.Central.AuctionObject;
import AdExchange.Cache.Central.BreakView;
import AdExchange.Cache.Central.BudgetLimiter;
import AdExchange.Cache.Central.CampaignBuyAuctionInfo;
import AdExchange.Cache.Central.ChannelBundlingInfo;
import AdExchange.Cache.Central.GridUtils;
import AdExchange.Cache.Central.PlacementAttribute;
import AdExchange.Cache.Central.Spot;

import AdExchange.Core.AuctionTally;
import AdExchange.Core.AuctionTallyMap;
import AdExchange.Core.DayOfWeek;
import AdExchange.Core.Money;
import AdExchange.Creative.CreativeRotation;

/**
 * Mutable container class representing all budget accounting constraints used
 * in an auction, including spending and adjacency constraints. Implements the
 * parts of the auction algorithm that keeps track of budget and adjacency
 * during the auction and applies the constraints.
 */
class AuctionBudget
{
   /**
    * Constructor.
    * @param buyId Client Campaign Buy ID (for grid auctions only).
    * @param settings AuctionSettings for this auction.
    */
   public AuctionBudget(int buyId)
   {
      myCampaignBuyId = buyId;
   }
   
   /**
    * @return Spending data for our client CampaignBuy.
    * Only valid after an auction has been run (may return null).
    */
   public CampaignBuyAuctionTally clientTally()
   {
       return myClientBuyTally;
   }

   /**
    * Initialize auction budget data from an array of bids. Should be called
    * before first use.
    * @param bids Array of bids in this auction.
    */
   public void initBudget(AuctionObjectShadow[] bids)
   {
      myAuctionTalliesByCampaignID.clear();

      HashMap<Integer, CampaignBuyAuctionInfo> cbaiMap =
         new HashMap<Integer, CampaignBuyAuctionInfo>();
      
      // Reset some tallies to null. Otherwise, if there are no bids,
      // existing data get reused.
      myClientBuyTally = null;

      // Loop through all bids in the auction
      for (AuctionObjectShadow bid : bids)
      {
         AuctionObject ao = bid.auctionObj;
         CampaignBuyAuctionInfo cbai = ao.campaignBuy;
         int campaignID = cbai.campaignID;
         int campaignBuyID = cbai.campaignBuyID;
         int bwi = ao.spot.budgetWeekIndex;
         cbaiMap.put(campaignBuyID, cbai);

         CampaignAuctionTally campaign_tally = myAuctionTalliesByCampaignID.get(campaignID);

         // Create a CampaignAuctionSpending object if we haven't
         // created one for this Campaign yet.
         // NOTE: A campaign may have multiple media buys. Initialize campaign tally info based on
         // first one found since they all refer to same object.
         if (campaign_tally == null)
         {
            campaign_tally = new CampaignAuctionTally(cbai);
            myAuctionTalliesByCampaignID.put(campaignID, campaign_tally);
         }

         // Create a CampaignBuyAuctionSpending object if we haven't
         // created one for this Campaign Buy yet. This will initialize
         // weekly spending info.
         // Note: A media buy may have multiple bids. Initialize media buy tally info based
         // on first one found since they all refer to same object. 
         CampaignBuyAuctionTally cb_tally = campaign_tally.talliesByCampaignBuyID.get(campaignBuyID);
         if (cb_tally == null)
         {
            cb_tally = new CampaignBuyAuctionTally(cbai);
            campaign_tally.talliesByCampaignBuyID.put(campaignBuyID, cb_tally);
            if (campaignBuyID == myCampaignBuyId)
                myClientBuyTally = cb_tally;
         }

         // Create a weekly spending data object if we haven't created it
         // one for this spot's week's index yet. If there was no spending info
         // from the CampaignBuyAuctionInfo object, then set initial spending 
         // to be zero.
         WeeklyAuctionTally week = cb_tally.weeklyTally.get(bwi);
         if (week == null)
         {
             cb_tally.createEmptyWeeklyTally(bwi);
         }
         
      } // END Loop through bids

      // Adjust each Campaign Budget of each buy in the auction so as not to
      // over-spend the campaign.  Also, calculate daily and weekly budgets
      // for each buy.
      for (CampaignBuyAuctionInfo cbai : cbaiMap.values())
      {
         // Record budget numbers for daily/weekly spending debugging.
         CampaignAuctionTally campaignTally = myAuctionTalliesByCampaignID.get(cbai.campaignID);
         CampaignBuyAuctionTally buyTally = campaignTally.talliesByCampaignBuyID.get(cbai.campaignBuyID);
         for (WeeklyAuctionTally week : buyTally.weeklyTally.values())
         {
             week.setWeeklyLimits(cbai);
         }
      }
   }

   /**
    * Check whether the current bid is within our budget constraints. This
    * includes overall campaign, buy weekly, and buy daily limits.
    * 
    * @param bidder Current bidder
    * @param curToggles Current toggles of auction client (grid).
    * @return true if passes budget limits. If false, the bidder's status is set
    *         to the appropriate reason for failure.
    */
   public boolean budgetLimitsPass(
      AuctionObjectShadow bidder,
      AuctionViewToggles curToggles)
   {
      if (bidder.auctionObj.campaignBuy.mediaBuyType == MediaBuyType.SPENDING_LIMITED)
         return spendingLimitsPass(bidder, curToggles);
      else
         return impressionLimitsPass(bidder, curToggles);

   }
   /**
    * Add to budget and impression numbers for a winning bidder.
    * 
    * @param bid AuctionObjectShadow containing update info.
    */
   public void addToWinnerBudgetTotals(AuctionObjectShadow bid)
   {
      updateWinnerBudgetTotals(bid, false);
   }

   /**
    * Unroll previously added budget and impression numbers for a bidder
    * that we added via addToWinnerBudgetTotals()
    * 
    * @param bid AuctionObjectShadow containing update info.
    */
   public void unrollWinnerBudgetTotals(AuctionObjectShadow bid)
   {
      updateWinnerBudgetTotals(bid, true);
   }

   /**
    * Update budget and impression numbers for a winning bidder. If this is an
    * unrolling operation, impressions and cost will be negative, allowing Interconnect
    * and Mirroring processing to be able to unroll potential winnings.
    * 
    * @param bid AuctionObjectShadow containing update info.
    * @param isUnroll If true, we are removing a winner from our totals.
    */
   private void updateWinnerBudgetTotals(AuctionObjectShadow bid, 
                                         boolean isUnroll)
                                        
   {
      AuctionObject ao = bid.auctionObj;
      Spot spot = ao.spot;
      BreakView breakView = spot.breakView;
      CampaignBuyAuctionInfo cbai = ao.campaignBuy;
      int cId = cbai.campaignID;
      int buyId = cbai.campaignBuyID;
      int bwi = spot.budgetWeekIndex;
      int dow = spot.budgetDayOfWeek;
      Money spotCost = (isUnroll ? bid.auctionCost().negate() : bid.auctionCost());
      long spotImpressions = (isUnroll ? -(spot.totalADViews) : spot.totalADViews);

      // Update per-campaign info
      CampaignAuctionTally campaignTally = myAuctionTalliesByCampaignID.get(cId);
      campaignTally.spending = campaignTally.spending.plus(spotCost);
      campaignTally.impressions += spotImpressions;
      // Update per-buy info
      CampaignBuyAuctionTally mediaBuyTally = campaignTally.talliesByCampaignBuyID.get(buyId);
      mediaBuyTally.spending = mediaBuyTally.spending.plus(spotCost);
      mediaBuyTally.impressions += spotImpressions;
      if (spot.isFuture)
      {
          mediaBuyTally.futureAvailSpending = mediaBuyTally.futureAvailSpending.plus(spotCost);
          mediaBuyTally.futureAvailImpressions += spotImpressions;
      }
      else
      {
          mediaBuyTally.curAvailSpending = mediaBuyTally.curAvailSpending.plus(spotCost);
          mediaBuyTally.curAvailImpressions += spotImpressions;
      }
      
      // Update daypart/channel/demographic budget limit tallies
      mediaBuyTally.daypartTally.updateTally(spot.daypartID, spotCost, spotImpressions);
      mediaBuyTally.channelTally.updateTally(breakView.channelId, spotCost, spotImpressions);
      mediaBuyTally.demographicTally.updateTally(spot.qualityType(), spotCost, spotImpressions);
      
      // We'll only track win times if we care about proximity restrictions
      if (cbai.proximityRestrictionSeconds > 0)
      {
         mediaBuyTally.updateAuctionWinTimes(breakView.channelId, spot.schedTimeLocal.getTime(), breakView.id, 
            spot.duration, isUnroll);
      }
      
      // Update creative tallies
      mediaBuyTally.updateCreativeTally(bid, isUnroll);

      // Update daily/weekly budget & impression totals.
      // Note that we add to the buy, the week and the day within the week totals.
      WeeklyAuctionTally wk = mediaBuyTally.weeklyTally.get(bwi);
      if (wk != null)
      {
         AuctionTally day = wk.dailyTallies[dow];
         wk.spending = wk.spending.plus(spotCost);
         wk.impressions += spotImpressions;
         wk.updateDaypartTally(spot.daypartID, spotCost, spotImpressions);
         wk.updateChannelTally(breakView.channelId, spotCost, spotImpressions);
         wk.updateDemographicTally(spot.qualityType(), spotCost, spotImpressions);
         day.spending = day.spending.plus(spotCost);
         day.impressions += spotImpressions;
      }
   }

   /**
    * Add tallies to the appropriate break, daypart, program, 
    * bundling, product attribute mappings.  These may be undone
    * via unrollWinnerConentTotals().
    * This method is NOT thread safe.
    * @param ao Winning AuctionObject bidder.
    * @param returnDebugString if true return a string that dumps
    * the output of the winning attributes and breaks.
    * @return a debug string if returnDebugString is true, otherwise null.
    */
   public String addToWinnerContentTotals(AuctionObjectShadow aos,
                                          boolean returnDebugString)
   {
      return updateWinnerContentTotals(aos, false, returnDebugString);
   }

   /**
    * Unroll tallies to the appropriate break, daypart, program, 
    * bundling, product attribute mappings.  These should have been
    * added via addToWinnerConentTotals().
    * 
    * This method is NOT thread safe.
    * @param ao Winning AuctionObject bidder.
    * @param returnDebugString if true return a string that dumps
    * the output of the winning attributes and breaks.
    * @return a debug string if returnDebugString is true, otherwise null.
    */
   public String unrollWinnerContentTotals(AuctionObjectShadow aos,
                                           boolean returnDebugString)
   {
      return updateWinnerContentTotals(aos, true, returnDebugString);
   }

   /**
    * Update our budget tallies with data from a winning bidder.
    * This method is NOT thread safe.
    * @param ao Winning AuctionObject bidder.
    * @param isUnroll If true, we are removing a winner from our totals.
    */
   private String updateWinnerContentTotals(AuctionObjectShadow aos,
                                            boolean isUnroll,
                                            boolean returnDebugString)
   {
      AuctionObject ao = aos.auctionObj;
      Spot spot = ao.spot;
      BreakView bk = spot.breakView;
      CampaignBuyAuctionInfo cb = ao.campaignBuy;
      int cId = cb.campaignID;
      int buyId = cb.campaignBuyID;
      int creativeId = aos.getSelectedCreativeId();

      // Update per-campaign info
      CampaignAuctionTally campaignTally = myAuctionTalliesByCampaignID.get(cId);

      // Update per-buy info
      CampaignBuyAuctionTally mediaBuyTally = campaignTally.talliesByCampaignBuyID.get(buyId);

      if (isUnroll)
      {
         campaignTally.removeBreak(bk.id);

         mediaBuyTally.removeWinInBreak(bk.id);
         mediaBuyTally.removeWinInProgram(bk.programViewId(), bk.zoneId);
         mediaBuyTally.removeWonInDaypart(spot);
         mediaBuyTally.channelBundlingInfo.removeChannelWin(aos);

         if (cb.creativeDuration == 15)
         {
            mediaBuyTally.remove15SecWinInBreak(aos, bk.id);
         }
      }
      else
      {
         campaignTally.addBreak(bk.id);

         mediaBuyTally.setWinInBreak(bk.id);
         mediaBuyTally.setWinInProgram(bk.programViewId(), bk.zoneId);
         mediaBuyTally.setWonInDaypart(spot);
         mediaBuyTally.channelBundlingInfo.setChannelWin(aos);
         if (cb.creativeDuration == 15)
         {
            mediaBuyTally.set15SecWinInBreak(aos, bk.id);
         }
      }
      // Update placement info
      String dbgMsg = updateWinnerPlacementAttrs(cb, spot, creativeId, isUnroll,
                                                 returnDebugString);

      if (returnDebugString)
      {
         return "Buy=" + buyId + " Cr=" + creativeId + " Av=" + spot.id + " " +
            mediaBuyTally.toString() + " " + dbgMsg;
      }

      return null;
   }
   
   /**
    * Check whether the current bid is within our budget constraints. This
    * includes overall campaign, buy weekly, and buy daily limits.
    * 
    * @param bidder Current bidder
    * @param curToggles Current toggles of auction client (grid).
    * @return true if passes budget limits. If false, the bidder's status is set
    *         to the appropriate reason for failure.
    */
   public boolean spendingLimitsPass(
      AuctionObjectShadow bidder,
      AuctionViewToggles curToggles)
   {
      AuctionObject ao = bidder.auctionObj;
      CampaignBuyAuctionInfo cbai = ao.campaignBuy;
      if (!curToggles.useBudget && myCampaignBuyId == cbai.campaignBuyID)
      {
         // If we are viewing the effects of leaving disregarding budget,
         // if this bidder is our client AdBuy, we always succeed this check.
         return true;
      }
      Money bidCost = bidder.auctionCost();
      
      // Check that we haven't gone over our campaign spending limit
      CampaignAuctionTally campaignTally = myAuctionTalliesByCampaignID.get(cbai.campaignID);
      if (campaignTally.spending.plus(bidCost).isGreaterThan(cbai.effectiveCampaignSpendingLimit))
      {
         bidder.auctionState = AuctionStatus.EXCEED_CAMPAIGN_BUDGET;
         return false;
      }
      // Check that we haven't gone over our media buy spending limit
      CampaignBuyAuctionTally mediaBuyTally =
         campaignTally.talliesByCampaignBuyID.get(cbai.campaignBuyID);
      if (mediaBuyTally.spending.plus(bidCost).isGreaterThan(cbai.effectiveSpendingLimit))
      {
          bidder.auctionState = AuctionStatus.EXCEED_BUY_BUDGET;
          return false;
      }
      // Check spending limit against the daypart
      Spot spot = ao.spot;
      int dpID = spot.daypartID;
      if (cbai.hasDaypartSpendLimit(dpID))
      {
         Money dpSp = mediaBuyTally.daypartTally.currentSpending(dpID);
         if (cbai.exceedsDaypartSpendingLimit(dpID, dpSp.plus(bidCost)))
         {
            bidder.auctionState = AuctionStatus.EXCEED_DAYPART_SPEND_LIMIT;
            return false;
         }
      }
      // Check spending limit against the channel
      int chanID = spot.breakView.channelId;
      if (cbai.hasChannelSpendLimit(chanID))
      {
         Money chanSp = mediaBuyTally.channelTally.currentSpending(chanID);
         if (cbai.exceedsChannelSpendingLimit(chanID, chanSp.plus(bidCost)))
         {
            bidder.auctionState = AuctionStatus.EXCEED_CHANNEL_SPEND_LIMIT;
            return false;
         }
      }
      // Check spending limit against the demographics
      DemographicQualityType demographicQuality = spot.qualityType();
      if (cbai.hasDemographicSpendLimit(demographicQuality))
      {
         Money demographicSpend =
            mediaBuyTally.demographicTally.currentSpending(demographicQuality);
         if (cbai.exceedsDemographicSpendingLimit(demographicQuality,
            demographicSpend.plus(bidCost)))
         {
            // NOTE: Technically, demographicQualityId can take on three values:
            // UNRATED_NETWORK, MISSING_DATA, and RATED.
            // At this time, RATED inventory will never have a limit associated
            // with it. The only supported limit is for UNRATED_NETWORK
            // inventory. If the system will support limits for RATED inventory,
            // then this code will need to change.
            if (demographicQuality == DemographicQualityType.UNRATED_NETWORK)
            {
               bidder.auctionState =
                  AuctionStatus.EXCEED_DEMOGRAPHIC_UNRATED_NETWORK_SPEND_LIMIT;
            }
            else
            {
               bidder.auctionState =
                  AuctionStatus.EXCEED_DEMOGRAPHIC_MISSING_DATA_SPEND_LIMIT;
            }
            
            return false;
         }
      }
      // Check weekly spending limit
      int bwi = ao.spot.budgetWeekIndex;
      WeeklyAuctionTally wkSpend = mediaBuyTally.weeklyTally.get(bwi);
      Money effectiveWeeklySpendingLimit = cbai.effectiveWeeklyRemainingSpendingLimits.get(bwi);
      if (effectiveWeeklySpendingLimit != null &&
          wkSpend.spending.plus(bidCost).isGreaterThan(effectiveWeeklySpendingLimit))
      {
         bidder.auctionState = AuctionStatus.EXCEED_WEEKLY_BUDGET;
         return false;
      }
      // Check weekly spending limit against the daypart
      if (wkSpend != null && cbai.hasWeeklyDaypartSpendingLimit(bwi, dpID))
      {
         Money dpSpend = wkSpend.spendingForDaypart(dpID);
         if (cbai.exceedsWeeklyDaypartSpendingLimit(bwi, dpID, dpSpend.plus(bidCost)))
         {
            bidder.auctionState = AuctionStatus.EXCEED_WEEKLY_DAYPART_SPEND_LIMIT;
            return false;
         }
      }
      // Check weekly spending limit against the channel
      if (wkSpend != null && cbai.hasWeeklyChannelSpendingLimit(bwi, chanID))
      {
         Money chanSpend = wkSpend.spendingForChannel(chanID);
         if (cbai.exceedsWeeklyChannelSpendingLimit(bwi, chanID, chanSpend.plus(bidCost)))
         {
            bidder.auctionState = AuctionStatus.EXCEED_WEEKLY_CHANNEL_SPEND_LIMIT;
            return false;
         }
      }
      // Check weekly spending limit againsst the demographics
      if (wkSpend != null && cbai.hasWeeklyDemographicSpendingLimit(bwi, demographicQuality))
      {
         Money demographicSpend = wkSpend.spendingForDemographic(demographicQuality);
         if (cbai.exceedsWeeklyDemographicSpendingLimit(bwi, demographicQuality, demographicSpend.plus(bidCost)))
         {
            // NOTE: Technically, demographicQualityId can take on three values:
            // UNRATED_NETWORK, MISSING_DATA, and RATED.
            // At this time, RATED inventory will never have a limit associated
            // with it. The only supported limit is for UNRATED_NETWORK
            // inventory. If the system will support limits for RATED inventory,
            // then this code will need to change.
            if (demographicQuality == DemographicQualityType.UNRATED_NETWORK)
            {
               bidder.auctionState =
                  AuctionStatus.EXCEED_DEMOGRAPHIC_UNRATED_NETWORK_WEEKLY_SPEND_LIMIT;
            }
            else
            {
               bidder.auctionState =
                  AuctionStatus.EXCEED_DEMOGRAPHIC_MISSING_DATA_WEEKLY_SPEND_LIMIT;
            }
            
            return false;
         }
      }
      // Check daily spending limit
      int dow = ao.spot.budgetDayOfWeek;
      AuctionTally day = wkSpend.dailyTallies[dow];
      if (day.spending.plus(bidCost).isGreaterThan(cbai.effectiveDailyRemainingSpendingLimit))
      {
         bidder.auctionState = AuctionStatus.EXCEED_DAILY_BUDGET;
         return false;
      }
      // We're within spending limits
      return true;
   }
   
   /**
    * Check whether winning this bid would exceed the impression goals for this
    * bidder's Campaign Buy. If false, the bidder's status is set to the
    * appropriate reason for failure.
    * 
    * @param bidder Current bidder
    * @param curToggles Current toggles of auction client (grid).
    * @return true if bid falls within campaign buy impressions goals. If false,
    *         the bidder's status is set to the appropriate reason for failure.
    */
   public boolean impressionLimitsPass(
      AuctionObjectShadow bidder,
      AuctionViewToggles curToggles)
   {
      AuctionObject ao = bidder.auctionObj;
      CampaignBuyAuctionInfo cbai = ao.campaignBuy;
      //
      // If this buy is not limited by its impressions goal, return true.
      if (cbai.mediaBuyType != MediaBuyType.IMPRESSION_LIMITED)
          return true;
      
      // If this is our client campaign buy and we're not checking budget &
      // goals, always return true.
      if (!curToggles.useBudget && myCampaignBuyId == cbai.campaignBuyID)
         return true;
      
      Spot spot = ao.spot;
      // Check overall Campaign impression limit
      CampaignAuctionTally campaignTally = myAuctionTalliesByCampaignID.get(cbai.campaignID);
      if ((campaignTally.impressions + spot.totalADViews) > cbai.effectiveCampaignImpressionLimit)
      {
         bidder.auctionState = AuctionStatus.EXCEED_CAMPAIGN_IMPRESSION_LIMIT;
         return false;
      }
      // Check that we haven't gone over our media buy impression limit
      CampaignBuyAuctionTally mediaBuyTally = campaignTally.talliesByCampaignBuyID.get(cbai.campaignBuyID);
      if ((mediaBuyTally.impressions + spot.totalADViews) > cbai.impressionLimit)
      {
         bidder.auctionState = AuctionStatus.EXCEED_BUY_IMPRESSION_LIMIT;
         return false;
      }
      // Check impression limit against the daypart
      int dpID = spot.daypartID;
      if (cbai.hasDaypartImpressionLimit(dpID))
      {
         long dpImp = mediaBuyTally.daypartTally.currentImpressions(dpID);
         if (cbai.exceedsDaypartImpressionLimit(dpID, dpImp + spot.totalADViews))
         {
            bidder.auctionState = AuctionStatus.EXCEED_DAYPART_IMPRESSION_LIMIT;
            return false;
         }
      }
      // Check impression limit against the channel
      int chanID = spot.breakView.channelId;
      if (cbai.hasChannelImpressionLimit(chanID))
      {
         long chanImp = mediaBuyTally.channelTally.currentImpressions(chanID);
         if (cbai.exceedsChannelImpressionLimit(chanID, chanImp + spot.totalADViews))
         {
            bidder.auctionState = AuctionStatus.EXCEED_CHANNEL_IMPRESSION_LIMIT;
            return false;
         }
      }
      // Check impression limit against the demographics
      DemographicQualityType demographicQuality = spot.qualityType();
      if (cbai.hasDemographicImpressionLimit(demographicQuality))
      {
         long demographicImpressions = mediaBuyTally.demographicTally.currentImpressions(demographicQuality);
         if (cbai.exceedsDemographicImpressionLimit(demographicQuality, demographicImpressions + spot.totalADViews))
         {            
            // NOTE: Technically, demographicQuality can take on three values:
            // UNRATED_NETWORK, MISSING_DATA, and RATED.
            // At this time, RATED inventory will never have a limit associated
            // with it. The only supported limit is for UNRATED_NETWORK
            // inventory. If the system will support limits for RATED inventory,
            // then this code will need to change.
            if (demographicQuality == DemographicQualityType.UNRATED_NETWORK)
            {
               bidder.auctionState =
                  AuctionStatus.EXCEED_DEMOGRAPHIC_UNRATED_NETWORK_IMPRESSION_LIMIT;
            }
            else
            {
               bidder.auctionState =
                  AuctionStatus.EXCEED_DEMOGRAPHIC_MISSING_DATA_IMPRESSION_LIMIT;
            }
            return false;
         }
      }
      // Check weekly impression limit
      int bwi = ao.spot.budgetWeekIndex;
      WeeklyAuctionTally wkSpend = mediaBuyTally.weeklyTally.get(bwi);
      Long effectiveWeeklyImpressionLimit = cbai.effectiveWeeklyRemainingImpressionLimits.get(bwi);
      if (effectiveWeeklyImpressionLimit != null && 
          wkSpend != null &&
          (wkSpend.impressions + spot.totalADViews) > effectiveWeeklyImpressionLimit)
      {
         bidder.auctionState = AuctionStatus.EXCEED_WEEKLY_IMPRESSION_LIMIT;
         return false;
      }
      // Check weekly impression limit against the daypart
      if (wkSpend != null && cbai.hasWeeklyDaypartImpressionLimit(bwi, dpID))
      {
         long dpImp = wkSpend.impressionsForDaypart(dpID);
         if (cbai.exceedsWeeklyDaypartImpressionLimit(bwi, dpID, dpImp + spot.totalADViews))
         {
            bidder.auctionState = AuctionStatus.EXCEED_WEEKLY_DAYPART_IMPRESSION_LIMIT;
            return false;
         }
      }
      // Check weekly impression limit against the channel
      if (wkSpend != null && cbai.hasWeeklyChannelImpressionLimit(bwi, chanID))
      {
         long chanImp = wkSpend.impressionsForChannel(chanID);
         if (cbai.exceedsWeeklyChannelImpressionLimit(bwi, chanID, chanImp + spot.totalADViews))
         {
            bidder.auctionState = AuctionStatus.EXCEED_WEEKLY_CHANNEL_IMPRESSION_LIMIT;
            return false;
         }
      }
      // Check weekly impression limit against the avail demographic quality type.
      if (wkSpend != null && cbai.hasWeeklyDemographicImpressionLimit(bwi, demographicQuality))
      {
         long demographicImpressions = wkSpend.impressionsForDemographic(demographicQuality);
         if (cbai.exceedsWeeklyDemographicImpressionLimit(bwi, demographicQuality, demographicImpressions + spot.totalADViews))
         {            
            // NOTE: Technically, demographicQualityId can take on three values:
            // UNRATED_NETWORK, MISSING_DATA, and RATED.
            // At this time, RATED inventory will never have a limit associated
            // with it. The only supported limit is for UNRATED_NETWORK
            // inventory. If the system will support limits for RATED inventory,
            // then this code will need to change.
            if (demographicQuality == DemographicQualityType.UNRATED_NETWORK)
            {
               bidder.auctionState =
                  AuctionStatus.EXCEED_DEMOGRAPHIC_UNRATED_NETWORK_WEEKLY_IMPRESSION_LIMIT;
            }
            else
            {
               bidder.auctionState =
                  AuctionStatus.EXCEED_DEMOGRAPHIC_MISSING_DATA_WEEKLY_IMPRESSION_LIMIT;
            }
            
            return false;
         }
      }
      // Check daily impression limit
      int dow = ao.spot.budgetDayOfWeek;
      AuctionTally day = wkSpend.dailyTallies[dow];
      if ((day.impressions + spot.totalADViews) > cbai.effectiveDailyRemainingImpressionLimit)
      {
         bidder.auctionState = AuctionStatus.EXCEED_DAILY_IMPRESSION_LIMIT;
         return false;
      }
      // We're within impression limits
      return true;
   }
   
   /**
    * Check the auto adjacency rules for this bidder for this spot.
    * The default rule enforces 1 media buy win per break.  However, if
    * both operator and advertiser allow it, a (typically 15-second) media
    * buy may play 2 times in the same break.
    * 
    * @param bidder Current bidder
    * @return true if bid would qualify. If false, the bidder's status is set to
    *         the appropriate reason for failure.
    */
   public boolean autoAjacencyPasses(AuctionObjectShadow bidder)
   {
       AuctionObject ao = bidder.auctionObj;
       CampaignBuyAuctionInfo cb = ao.campaignBuy;
       CampaignAuctionTally camp = myAuctionTalliesByCampaignID.get(cb.campaignID);
       CampaignBuyAuctionTally cBuySpending = camp.talliesByCampaignBuyID.get(cb.campaignBuyID);
       int brID = ao.spot.breakView.id;
       int wins = cBuySpending.getWinsInBreak(brID);
       if (wins > 0)
       {
           if (ao.allow2WinsPerBreak && wins == 1)
           {
               // If this buy has 1 win in this break already
               // AND the bid allows a double win, this bid passes.
               return true;
           }
           // We already have one win in this break: this fails.
           bidder.auctionState = AuctionStatus.ADJ_BREAK;
           return false;
       }
       // We don't have a win in this break: this passes.
       return true;
   }

   /**
    * Check the various adjacency rules for this bidder for this spot.
    * 
    * @param bidder Current bidder
    * @param curToggles Current toggles of auction client (grid).
    * @return true if bid would qualify. If false, the bidder's status is set to
    *         the appropriate reason for failure.
    */
   public boolean advertiserAjacencyPasses(
      AuctionObjectShadow bidder,
      AuctionViewToggles curToggles)
   {
      AuctionObject ao = bidder.auctionObj;
      CampaignBuyAuctionInfo cbai = ao.campaignBuy;
      if (!curToggles.usePlacement && myCampaignBuyId == cbai.campaignBuyID)
      {
         // If we are viewing the effects of leaving out placement rules,
         // if this bidder is our client AdBuy, we always succeed this check.
         return true;
      }

      Spot spot = ao.spot;
      BreakView bk = spot.breakView;
      AuctionAdjacencyRules rules = cbai.adjacencyRules;
      CampaignAuctionTally campignTally =
         myAuctionTalliesByCampaignID.get(cbai.campaignID);
      CampaignBuyAuctionTally buyTally = campignTally.talliesByCampaignBuyID.get(cbai.campaignBuyID);

      // Easy check for no rules
      if (rules == AuctionAdjacencyRules.NONE)
      {
         return true;
      }

      // Check whether another spot from our campaign is in this break.
      if (rules.notInSameCampaign)
      {
         if (campignTally.containsBreak(bk.id))
         {
            bidder.auctionState = AuctionStatus.ADJ_CAMPAIGN;
            return false;
         }
      }
      // Check that count of spots from our campaign buy in this break's program
      // doesn't equal or exceed the limit.
      if (rules.numTimesInProgram != AuctionAdjacencyRules.NO_LIMIT)
      {
         int wins = buyTally.getWinsInProgram(bk.program().viewId(), bk.zoneId);
         if (wins >= rules.numTimesInProgram)
         {
            bidder.auctionState = AuctionStatus.ADJ_PROGRAM;
            return false;
         }
      }
      // Check that the count of spots from our campaign buy in this break's daypart
      // doesn't equal or exceed the limit.
      if (rules.numTimesInDaypart != AuctionAdjacencyRules.NO_LIMIT)
      {
         int wins = buyTally.winsInDaypart(spot);
         if (wins >= rules.numTimesInDaypart)
         {
            bidder.auctionState = AuctionStatus.ADJ_DAYPART;
            return false;
         }
      }
      return true;
   }

   /**
    * Check for time-based proximity conflict on channel. Restricts how close wins can be from each other.
    * @param bidder Current bidder
    * @return true if bid would qualify. If false, the bidder's status is set to
    *         the appropriate reason for failure.
    */
   public boolean proximityRestrictionPasses(AuctionObjectShadow bidder)
   {
      AuctionObject ao = bidder.auctionObj;
      CampaignBuyAuctionInfo cbai = ao.campaignBuy;
      // Check for no restrictions
      if (cbai.proximityRestrictionSeconds <= 0)
         return true;
      
      Spot spot = ao.spot;
      BreakView bk = spot.breakView;
      long proximityTime = cbai.proximityRestrictionSeconds * 1000;
      long spotTime = bk.myStartLocal.getTime();
      CampaignAuctionTally campignTally = myAuctionTalliesByCampaignID.get(cbai.campaignID);
      CampaignBuyAuctionTally buyTally = campignTally.talliesByCampaignBuyID.get(cbai.campaignBuyID);
      // Get list of previous wins on the channel 
      TreeMap<Long, SpotProximityData> channelWins = buyTally.getChannelWinTimes().get(bk.channelId);
      if (channelWins == null)
         return true;
      // Walk our list of previous wins and see if current bidder is in conflict
      for (Map.Entry<Long,SpotProximityData> entry : channelWins.entrySet())
      {
         long prevWinTime = entry.getKey();
         SpotProximityData prevBreak = entry.getValue();
         // Check if difference in time is too small
         if (Math.abs(spotTime - prevWinTime) < proximityTime)
         {
            // Special case. Don't disqualify if bid is second 15-second spot in same break
            if (!ao.allow2WinsPerBreak || 
               !(spot.duration == 15) || !(prevBreak.duration() == 15) ||
               (bk.id != prevBreak.breakID()))
            {
               bidder.auctionState = AuctionStatus.FAILED_CHANNEL_PROXIMITY;
               return false;
            }
         }
         else 
         {
            // Minor performance. Our table of previous win times is sorted by time ascending. All entries
            // after this one will also be greater than the proximity delta.
            if (prevWinTime > spotTime)
               return true;
         }
      }      
      return true;
   }
   
   /**
    * Check whether a bid passes product-type placement adjacency.
    * We'll check attributes for each creative associated with this bid.
    * If any passes, then the bid passes.
    * @param aos Bidder
    * @return true if this bidder's product attributes do not match any in the
    *         spot's break.
    */
   public boolean productAttributesPass(AuctionObjectShadow aos)
   {
      AuctionObject ao = aos.auctionObj;
      BreakView bk = ao.spot.breakView;
      int bkID = bk.id;

      // The AOS contains a subset of the CBs list of creatives.
      // If there aren't any creatives in the subset list, then
      // the CB can't win the bid.
      Collection<Integer> creativeIDs = aos.getCreativeIds();
      if (creativeIDs.isEmpty())
      {
         aos.auctionState = AuctionStatus.NO_BID_CREATIVE_ID;
         return false;
      }

      // Ignore product attributes if this CB has
      // already won 1 bid in this break AND allows
      // 2 (15 second) wins in a break.
      int cId = ao.campaignBuy.campaignID;
      int buyId = ao.campaignBuy.campaignBuyID;
      CampaignAuctionTally campSpending = myAuctionTalliesByCampaignID.get(cId);
      CampaignBuyAuctionTally cBuySpending = campSpending.talliesByCampaignBuyID.get(buyId);

      int numWinsInBrk = cBuySpending.getWinsInBreak(bkID);
      if (numWinsInBrk == 1 && ao.allow2WinsPerBreak)
      {
         // Find the other winner & set our bid's creative to be that winner's creative.
         AuctionObjectShadow otherWin = cBuySpending.get15SecWinInBreak(bkID);
         if (otherWin != null)
         {
            // Creative selection happens later - ensure there is a choice of 1.
            // This sets a single creativeId in the bid's list of creativeIds.
            aos.setCreativeIds(otherWin.getSelectedCreativeId());
         }
         return true;
      }

      Map<PlacementAttribute, Integer> winnerAttrMap = myPlacementAttrsByBreakID.get(bkID);
      if (winnerAttrMap == null || winnerAttrMap.isEmpty())
      {
         // No winners here with attributes yet. OK.
         return true;
      }

      // Evaluate each creative for this bid against the placement
      // attributes of current winners.  If there are 0
      // creatives remaining after pruning, then this bid doesn't
      // pass product attribute constraints.
      creativeIDs = aos.pruneCreatives(winnerAttrMap.keySet());

      // If we pruned all creatives due to product attribute adjacency,
      // then this bid does not pass.
      if (creativeIDs.isEmpty())
      {
         // We pruned all creatives due to product attribute adjacency.
         aos.auctionState = AuctionStatus.ADJ_PRODUCT;
         return false;
      }
      return true;
   }
   
   /**
    * Get the Channel Bunding info for a buy for this auction.
    * @param buy we are querying.
    * @return ChannelBundlingInfo for this buy.
    */
   public ChannelBundlingInfo getChannelBudgetInfo(CampaignBuyAuctionInfo buy)
   {
       CampaignAuctionTally campSpending = myAuctionTalliesByCampaignID.get(buy.campaignID);
       CampaignBuyAuctionTally buySpending = campSpending.talliesByCampaignBuyID.get(buy.campaignBuyID);
       return buySpending.channelBundlingInfo;
   }

   /**
    * Reset all auction state for all our budget data. This should be called
    * before reusing this object for multiple auctions.
    */
   public void resetAuctionBudgetValues()
   {
      // Reset spending values for each Campaign and associated structures.
      for (CampaignAuctionTally cas : myAuctionTalliesByCampaignID.values())
      {
         cas.resetValues();
      }

      // Clear all placement info
      // Help garbage collection by nulling and recreating the map.
      myPlacementAttrsByBreakID = null;

      myPlacementAttrsByBreakID = new HashMap<Integer, Map<PlacementAttribute, Integer>>();
   }

   /**
    * Clear weekly initial spending values. This is done before running a
    * proposal auction, which must be done from the start of a broadcast week.
    */
   public void clearWeeklyValues()
   {
      for (CampaignAuctionTally cas : myAuctionTalliesByCampaignID.values())
      {
         for (CampaignBuyAuctionTally cbs : cas.talliesByCampaignBuyID.values())
         {
            for (WeeklyAuctionTally week : cbs.weeklyTally.values())
            {
               week.clearValues();
            }
         }
      }
   }

   /**
    * Update the placement attributes for this winner, so we can check placement
    * adjacency rules per break.
    * This method is NOT thread safe.
    * @param cb Winning CampaignBuy
    * @param spot Available spot that was won.
    * @param creativeId the ID of the creative that won.
    * @param isUnroll If true, unroll the attrs.  If false, add them.
    */
   private String updateWinnerPlacementAttrs(CampaignBuyAuctionInfo cb, Spot spot,
                                             int creativeId, boolean isUnroll,
                                             boolean returnDebugString)
   {
      BreakView bk = spot.breakView;
      int orgID = bk.orgId;
      int bkID = bk.id;
      PlacementAttribute winnerAttrs = cb.getPlacementAttrsForOrg(creativeId, 
                                                                  orgID);
      if (winnerAttrs != null)
      {
         Integer count = 0;

         Map<PlacementAttribute, Integer> attrMap = myPlacementAttrsByBreakID.get(bkID);

         if (isUnroll)
         {
            if (attrMap != null)
            {
               // Remove these attributes
               count = attrMap.remove(winnerAttrs);

               // If we found them and there is more than 1 instance, then 
               // replace them with the new count.
               if (count != null)
               {
                  if (--count > 0)
                  {
                     attrMap.put(winnerAttrs, count);
                  }
               }
                     
            }
         }
         else // ! isUnroll - add these attributes to our map
         {
            if (attrMap == null)
            {
               attrMap = new HashMap<PlacementAttribute, Integer>();
               myPlacementAttrsByBreakID.put(bkID, attrMap);
            }
            else
            {
               // If we already have this set, get the # of instances stored.
               count = attrMap.get(winnerAttrs);

               // If we don't have this set, it'll get a count of 1
               if (count == null)
                  count = 0;
            }
            attrMap.put(winnerAttrs, ++count);
         }
      }

      if (returnDebugString)
         return attrsForBreak(bkID);

      return null;
   }

   private String attrsForBreak(int bkID)
   {
      StringBuilder sb = new StringBuilder();
      sb.append("(Brk=").append(bkID).append(" Attrs:");

      Map<PlacementAttribute, Integer> mapOfAttrs = myPlacementAttrsByBreakID.get(bkID);
      if (mapOfAttrs == null || mapOfAttrs.isEmpty())
      {
         sb.append("None");
      }
      else
      {
         int num = 0;
         for (PlacementAttribute attrs : mapOfAttrs.keySet())
         {
            List<Integer> bools = attrs.getBooleanAttrs();
            for (int attr : bools)
            {
               if (num++ > 0)
                  sb.append(",");
               sb.append(attr);
            }
         }
      }
      sb.append(")");

      return sb.toString();
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
      // Initialize return map
      Map<DayOfWeek, AuctionTally> rtnMap =
         new EnumMap<DayOfWeek, AuctionTally>(DayOfWeek.class);
      for (DayOfWeek day : DayOfWeek.values())
      {
         rtnMap.put(day, new AuctionTally());
      }
      // Get spending data for campaign
      CampaignAuctionTally campaignSpending = myAuctionTalliesByCampaignID.get(campaignID);
      if (campaignSpending == null)
      {
         return rtnMap;
      }
      // Get spending data for buy
      CampaignBuyAuctionTally buyTally = campaignSpending.talliesByCampaignBuyID.get(buyID);
      if (buyTally == null)
      {
         return rtnMap;
      }
      // For each day of week, add up the spending and impressions
      // over the weeks of the buy.
      Map<Integer, WeeklyAuctionTally> weekMap = buyTally.weeklyTally;
      for (DayOfWeek day : DayOfWeek.values())
      {
         AuctionTally dowSum = rtnMap.get(day);
         int dayIndex = day.dbValue;
         for (Integer week : weekMap.keySet())
         {
            WeeklyAuctionTally wkSpent = weekMap.get(week);
            AuctionTally dayInfo = wkSpent.dailyTallies[dayIndex];
            dowSum.spending = dowSum.spending.plus(dayInfo.spending);
            dowSum.impressions += dayInfo.impressions;
         }
      }
      return rtnMap;
   }
   
   /**
    * Print all current spending info by Campaign.
    * 
    * @return String describing spending state.
    */
   public String dumpSpending()
   {
      StringBuilder sb = new StringBuilder();
      for (Integer cid : myAuctionTalliesByCampaignID.keySet())
      {
         CampaignAuctionTally cas = myAuctionTalliesByCampaignID.get(cid);
         sb.append(dumpCampaignSpending(cas, cid));
      }
      sb.append("\n");
      return sb.toString();
   }
   
   /**
    * Print all auction spending info about a Campaign,
    * by CampaignBuy.
    * @param cas CampaignAuctionSpending info to read.
    * @param cid Campaign ID.
    * @return Spending printout.
    */
   public static String dumpCampaignSpending(CampaignAuctionTally cas, int cid)
   {
      StringBuilder sb = new StringBuilder();
      sb.append("\n==> Campaign #" + cid);
      if (cas.mediaBuyType() == MediaBuyType.SPENDING_LIMITED)
         sb.append(TAB1 + "Campaign spending limit: " + cas.spendingLimit());
      else
         sb.append(TAB1 + "Campaign impression limit: " + String.format("%,d", cas.impressionLimit()));
      sb.append(TAB1 + "Pre-auction campaign spending/impressions: " + 
         cas.initialSpending() + "/" + String.format("%,d", cas.initialImpressions()));
      for (CampaignBuyAuctionTally cbTally : cas.talliesByCampaignBuyID.values())
      {
         sb.append(dumpCampaignBuySpending(cbTally));
      }
      sb.append("\n");
      return sb.toString();
   }
   
   /**
    * Print to a StringBuilder all spending info about a CampaignBuy.
    * @param cas CampaignBuyAuctionSpending info to read.
    * @param buyID CampaignBuy ID.
    * @return Spending printout.
    */
   public static String dumpCampaignBuySpending(CampaignBuyAuctionTally cbs)
   {
      StringBuilder sb = new StringBuilder();
      CampaignBuyAuctionInfo cbai = cbs.getCampaignBuyAuctionInfo();
      // buy id and type
      sb.append(TAB1 + "** Campaign Buy #" + cbai.campaignBuyID + " (" + cbai.campaignBuyName + ") **");

      Calendar startCal = TimeUtils.utcCalendar();
      startCal.setTime(cbai.startDate);
      Calendar endCal = TimeUtils.utcCalendar();
      endCal.setTime(cbai.endDate);
      sb.append(TAB2 + "Start/End date: " + GridUtils.formatShortDate(startCal) + " - " + 
         GridUtils.formatShortDate(endCal));

      MediaBuyType mediaBuyType = cbai.mediaBuyType;
      sb.append(TAB2 + "Type: " + mediaBuyType.name());
      
      // buy overall and daily effective limits
      if (mediaBuyType == MediaBuyType.SPENDING_LIMITED)
      {
         sb.append(TAB2 + "Buy spending limit (effective): " + cbai.effectiveSpendingLimit);
         sb.append(TAB2 + "Daily spending limit (effective): " + cbai.effectiveDailyRemainingSpendingLimit);
      }
      else
      {
         sb.append(TAB2 + "Buy impression limit (effective): " + 
            String.format("%,d", cbai.effectiveImpressionLimit));
         sb.append(TAB2 + "Daily impression Limit (effective): " + 
            String.format("%,d", cbai.effectiveDailyRemainingImpressionLimit));
      }
      
      // buy overall tallies
      float avgCpm = AuctionUtils.calculateCPM(cbs.initialImpressions(), cbs.initialSpending());
      sb.append(TAB2 + "Buy total spend/impr (pre-auction):  " + cbs.initialSpending() + "/" + 
         String.format("%,d", cbs.initialImpressions()));
      sb.append(" (avg. prev. CPM:" + String.format("$%.2f)", avgCpm));
      sb.append(TAB2 + "Buy total spend/impr (post-auction): " + cbs.spending + "/" + 
         String.format("%,d", cbs.impressions));
      sb.append(TAB2 + "Current/Future Avail Spend: " + cbs.curAvailSpending);
      sb.append("/" + cbs.futureAvailSpending);
      sb.append(TAB2 + "Current/Future Avail Impr: " + String.format("%,d", cbs.curAvailImpressions));
      sb.append("/" + String.format("%,d", cbs.futureAvailImpressions));

      // overall channel caps and tallies
      if (mediaBuyType == MediaBuyType.SPENDING_LIMITED)
         sb.append(TAB2 + "Buy spending caps by channel:" + cbai.getChannelLimits().dbgSpendingString());
      else
         sb.append(TAB2 + "Buy impression caps by channel: " + cbai.getChannelLimits().dbgImpressionString());
      sb.append(TAB3 + "Buy spend/impr by channel (pre-auction) : " + cbs.initialChannelTally.toString());
      sb.append(TAB3 + "Buy spend/impr by channel (post-auction): " + cbs.channelTally.toString());
      
      // overall daypart caps and tallies
      if (mediaBuyType == MediaBuyType.SPENDING_LIMITED)
         sb.append(TAB2 + "Buy spending caps by daypart:" + cbai.getDaypartLimits().dbgSpendingString());
      else
         sb.append(TAB2 + "Buy impression caps by daypart: " + cbai.getDaypartLimits().dbgImpressionString());
      sb.append(TAB3 + "Buy spend/impr by daypart (pre-auction) : " + cbs.initialDaypartTally.toString());
      sb.append(TAB3 + "Buy spend/impr by daypart (post-auction): " + cbs.daypartTally.toString());

      // overall demographic caps and tallies
      if (mediaBuyType == MediaBuyType.SPENDING_LIMITED)
         sb.append(TAB2 + "Buy spending caps by demographic:" + cbai.getDemographicLimits().dbgSpendingString());
      else
         sb.append(TAB2 + "Buy impression caps by demographic: " + cbai.getDemographicLimits().dbgImpressionString());
      sb.append(TAB3 + "Buy spend/impr by demographic (pre-auction) : " + cbs.initialDemographicTally.toString());
      sb.append(TAB3 + "Buy spend/impr by demographic (post-auction): " + cbs.demographicTally.toString());
      
      // Enforce Weekly budget caps?
      sb.append(TAB2 + "Channel/Daypart caps enforced by week? : " + cbai.enforeCapsByWeek);
      // Weekly caps and tallies
      for (WeeklyAuctionTally weeklyTally : cbs.weeklyTally.values())
      {
         sb.append(dumpWeeklySpending(cbai, weeklyTally));
      }
      
      // Creative tallies
      sb.append(dumpCreativeRotation(cbs));
      return sb.toString();
   }
   
   /**
    * Print to a StringBuilder all spending info about a CampaignBuy.
    * @param cbai the campaign buy auction info
    * @param weeklyTally the tallies for a given week
    * @return Spending printout.
    */
   public static String dumpWeeklySpending(CampaignBuyAuctionInfo cbai, WeeklyAuctionTally weeklyTally)
   {
       StringBuilder sb = new StringBuilder();
       int bwi = weeklyTally.budgetWeekIndex();
       sb.append(TAB2 + "Week " + bwi + " (" + GridUtils.formatBwiDateRange(weeklyTally.budgetWeekIndex()) + ")");
       sb.append(" - " + weeklyTally.activeDays() + " active days");
       MediaBuyType buyType = cbai.mediaBuyType;
       if (buyType == MediaBuyType.SPENDING_LIMITED)
       {
          sb.append(TAB3 + "Week spending limit (effective): " + weeklyTally.effWeeklySpendingLimit());
       }
       else
       {
          sb.append(TAB3 + "Week impr limit (effective): " + 
             String.format("%,d", weeklyTally.effWeeklyImpressionLimit()));
       }
       sb.append(TAB3 + "Week spend/impr (pre-auction) : " + weeklyTally.initialSpending());
       sb.append("/" + String.format("%,d", weeklyTally.initialImpressions()));
       sb.append(TAB3 + "Week spend/impr (post-auction): " + weeklyTally.spending);
       sb.append("/" + String.format("%,d", weeklyTally.impressions));
       sb.append(TAB3 + "Daily spend/impr (pre-auction)  (Mo-Su): ");
       sb.append(weeklyTally.daysInitialToString(SEP));
       sb.append(TAB3 + "Daily spend/impr (post-auction) (Mo-Su): ");
       sb.append(weeklyTally.daysSpendingToString(SEP));
       
       // Weekly Channel limits and tallies
       BudgetLimiter<Integer> wkChnLims = cbai.getWeeklyChannelLimits().getLimitsForWeek(bwi);
       if (buyType == MediaBuyType.SPENDING_LIMITED)
          sb.append(TAB3 + "Week spending limits by channel (effective): " + wkChnLims.dbgSpendingString());
       else
          sb.append(TAB3 + "Week impression limits by channel (effective): " + wkChnLims.dbgImpressionString());
       
       sb.append(TAB4 + "Week spend/impr by channel (pre-auction):  " + weeklyTally.getInitialChannelTally());
       sb.append(TAB4 + "Week spend/impr by Channel (post-auction): " + weeklyTally.getChannelTally());
       
       // Weekly Daypart limits and tallies
       BudgetLimiter<Integer> wkDayLims = cbai.getWeeklyDaypartLimits().getLimitsForWeek(bwi);
       if (buyType == MediaBuyType.SPENDING_LIMITED)
          sb.append(TAB3 + "Week spending limits by daypart (effective): " + wkDayLims.dbgSpendingString());
       else
          sb.append(TAB3 + "Week impression limits by daypart (effective): " + wkDayLims.dbgImpressionString());
       
       sb.append(TAB4 + "Week spend/impr by daypart (pre-auction):  " + weeklyTally.getInitialDaypartTally());
       sb.append(TAB4 + "Week spend/impr by daypart (post-auction): " + weeklyTally.getDaypartTally());
       
       // Weekly Demographic limits and tallies
       BudgetLimiter<DemographicQualityType> wkDemoLims = cbai.getWeeklyDemographicLimits().getLimitsForWeek(bwi);
       if (buyType == MediaBuyType.SPENDING_LIMITED)
          sb.append(TAB3 + "Week spending limits by demographic (effective): " + wkDemoLims.dbgSpendingString());
       else
          sb.append(TAB3 + "Week impression limits by demographic (effective): " + wkDemoLims.dbgImpressionString());       
       
       sb.append(TAB4 + "Week spend/impr by demographic (pre-auction):  " + weeklyTally.getInitialDemographicTally());
       sb.append(TAB4 + "Week spend/impr by demographic (post-auction): " + weeklyTally.getDemographicTally());
       return sb.toString();
   }
   
   /**
    * Dump spending per each creative of a Campaign Buy for this auction run.
    * @param cb CampaignBuy Auction tally data.
    * @return
    */
   private static String dumpCreativeRotation(CampaignBuyAuctionTally cb)
   {
       StringBuilder sb = new StringBuilder();
       sb.append(TAB2 + "Buy Spending by Creative: ");
       for (int crId : cb.creativeTallyMap.keySet())
       {
           CreativeTally cSpend = cb.creativeTallyMap.get(crId);
           CreativeRotation cr = cSpend.creativeRotation();
           sb.append(TAB2 + "CreativeId: #").append(crId);               
           sb.append(TAB3 + "TotalWinCount: " + String.format("%,d", cr.winCount()));
           sb.append(TAB3 + "CurSpending: " + cSpend.currentSpending().toString());
           sb.append(" (+ previous: " + cr.totalSpending().toString() + ")");
           sb.append(TAB3 + "CurImpressions: " + String.format("%,d", cSpend.currentImpressions()));
           sb.append(" (+ previous: " + String.format("%,d", cr.totalImpressions()) + ")");
           sb.append(TAB3 + "CurTargetImpressions: " + String.format("%,d", cSpend.currentTargetImpressions()));
           sb.append(TAB3 + "# Evaluations: " + String.format("%,d", cr.numEvaluations()));
       }
       return sb.toString();
   }
   
   /**
    * Create a Budget & Spending chart String for this auction.
    * Displays money only (no impression info).
    * @return Chart string suitable for EXCEL of auction spending.
    */
   public String dumpBudgetChart()
   {
       StringBuilder sb = new StringBuilder(BUDGET_CHART_HDR);
       for (CampaignAuctionTally campTally : myAuctionTalliesByCampaignID.values())
       {
          for (CampaignBuyAuctionTally buyTally : campTally.talliesByCampaignBuyID.values())
          {
             for (WeeklyAuctionTally weekTally : buyTally.weeklyTally.values())
             {
                dumpWeeklyBudgetInfo(sb, buyTally.getCampaignBuyAuctionInfo(), weekTally);
             }
          }
       }
       return sb.toString();
   }
   
   /**
    * Create weekly spending record suitable for EXCEL.
    * Each record is an auction week for a buy in a campaign.
    * @param sb StringBuilder to append to.
    * @param cbai Campaign Buy Auction Info.
    * @param weekTally Spending information for week.
    */
   private void dumpWeeklyBudgetInfo(
         StringBuilder sb, 
         CampaignBuyAuctionInfo cbai, 
         WeeklyAuctionTally weekTally
      )
   {
      sb.append(cbai.campaignID + SEP);
      sb.append(cbai.campaignBuyID + SEP);
      sb.append(weekTally.budgetWeekIndex() + SEP);
      sb.append(weekTally.activeDays() + SEP);
      if (cbai.mediaBuyType == MediaBuyType.SPENDING_LIMITED)
      {
         sb.append(cbai.dailyRemainingImpressionLimit + SEP);
         sb.append(weekTally.storedWeeklySpendingLimit() + SEP);
         sb.append(cbai.effectiveDailyRemainingSpendingLimit + SEP);
         sb.append(weekTally.effWeeklySpendingLimit() + SEP);
         sb.append(weekTally.initialSpending() + SEP);
         for (int i = WeeklyAuctionTally.FIRST_DAY_INDEX; i < WeeklyAuctionTally.DAY_ARRAY_LEN; i++)
         {
            AuctionTally day = weekTally.dailyTallies[i];
            sb.append(day.spending + SEP);
         }
         sb.append(weekTally.spending);
      }
      else
      {
         sb.append(String.format("%,d", cbai.dailyRemainingImpressionLimit) + SEP);
         sb.append(String.format("%,d", weekTally.storedWeeklyImpressionLimit()) + SEP);
         sb.append(String.format("%,d", cbai.effectiveDailyRemainingImpressionLimit) + SEP);
         sb.append(String.format("%,d", weekTally.effWeeklyImpressionLimit()) + SEP);
         sb.append(String.format("%,d", weekTally.initialImpressions()) + SEP);
         for (int i = WeeklyAuctionTally.FIRST_DAY_INDEX; i < WeeklyAuctionTally.DAY_ARRAY_LEN; i++)
         {
            AuctionTally day = weekTally.dailyTallies[i];
            sb.append(String.format("%,d", day.impressions) + SEP);
         }
         sb.append(String.format("%,d", weekTally.impressions));
      }
      sb.append("\n");
   }
   
   private static final String BUDGET_CHART_HDR = 
       "Camp|Buy|BWI|ActvDays|DayLimit|WeekLimit" +
          "|EffDayLimit|EffWeekLimit|InitAmount|Mo(1)|Tu(2)|We(3)|Th(4)|Fr(5)|Sa(6)|Su(7)|Total\n";
   
   /**
    * Get all spending results.
    * @return A map of CampaignAuctionSpending keyed by Campaign ID.
    */
   public Map<Integer, CampaignAuctionTally> getCampaignTallyResultsMap()
   {
      return myAuctionTalliesByCampaignID;
   }
   
   /******* CLASS MEMBERS **************/
   private static final String SEP = "|";
   private static final String TAB1 = "\n" + SEP;
   private static final String TAB2 = "\n" + SEP + SEP;
   private static final String TAB3 = "\n" + SEP + SEP + SEP;
   private static final String TAB4 = "\n" + SEP + SEP + SEP + SEP;


   /******* OBJECT MEMBERS *******/
   private final int myCampaignBuyId;
   private final Map<Integer, CampaignAuctionTally> myAuctionTalliesByCampaignID = 
      new HashMap<Integer, CampaignAuctionTally>();
   private Map<Integer, Map<PlacementAttribute, Integer>> myPlacementAttrsByBreakID =
      new HashMap<Integer, Map<PlacementAttribute, Integer>>();
   private CampaignBuyAuctionTally myClientBuyTally;

}
