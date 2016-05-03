/**
 * Part of a source code package originally written for the AdAuctionApp project.
 * Intended for use as a programming work sample file only.  Not for distribution.
 **/
package AdAuctionApp.AdBuyGrid;

import static AdAuctionApp.AdBuyGrid.AdConstraintSet.Constraint.AUCTION_WIN;
import static AdAuctionApp.AdBuyGrid.AdConstraintSet.Constraint.IN_AUDIENCE;
import static AdAuctionApp.AdBuyGrid.AdConstraintSet.Constraint.IN_PROGRAM;
import static AdAuctionApp.AdvertisingTarget.Central.CampBuyCalculateProgramsDTC.PROGRAM_CALCULATE_MSG;
import static AdAuctionApp.AdvertisingTarget.Central.CampBuyEstimateReachDTC.ESTIMATE_REACH_MSG;
import static AdAuctionApp.Auction.AuctionConstants.AUCTIONING_MSG;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ShortBuffer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;

import AdAuctionApp.AdBuyGrid.AdBuySoup.CableChannel;
import AdAuctionApp.AdBuyGrid.AdBuySoup.DaypartCell;
import AdAuctionApp.AdBuyGrid.AdConstraintSet.ImpressionType;
import AdAuctionApp.AdBuyGrid.Auction.AuctionViewToggles;
import AdAuctionApp.AdvertisingTarget.CampaignBuy;
import AdAuctionApp.AdvertisingTarget.TargetArea;
import AdAuctionApp.AdvertisingTarget.TargetAreaGeography;
import AdAuctionApp.AdvertisingTarget.Central.CampBuyCalculateProgramsDTC;
import AdAuctionApp.AdvertisingTarget.Central.CampBuyEstimateReachDTC;
import AdAuctionApp.AdvertisingTarget.Central.CampBuyMultiOperationDTC;
import AdAuctionApp.AdvertisingTarget.Central.CampBuyOperationDTC;
import AdAuctionApp.AdvertisingTarget.Central.CampaignBuyOperationNotificationIFace;
import AdAuctionApp.AdvertisingTarget.Central.CentralAdvertisingTargetManager;
import AdAuctionApp.AdvertisingTarget.Compiled.CriteriaCheckerIFace;
import AdAuctionApp.Logical.DemographicCalculator;
import AdAuctionApp.Attributes.AttributeConstants.AttributeCategoryType;
import AdAuctionApp.Attributes.AttributeConstants.AttributeDataType;
import AdAuctionApp.Auction.AuctionHtmlUtils;
import AdAuctionApp.Auction.AuctionObjectShadow;
import AdAuctionApp.Auction.Auctioneer;
import AdAuctionApp.Auction.CampaignBuyAuctionTally;
import AdAuctionApp.Auction.Auctioneer.AuctionType;
import AdAuctionApp.Auction.Central.AuctionOperationDTC;
import AdAuctionApp.Auction.Central.AuctionOperationNotificationIFace;
import AdAuctionApp.Auction.Central.CentralAuctionManager;
import AdAuctionApp.Cache.IndexedAttributeObject;
import AdAuctionApp.Cache.CacheConstants.CacheType;
import AdAuctionApp.Cache.Central.BreakView;
import AdAuctionApp.Cache.Central.CachedProgram;
import AdAuctionApp.Cache.Central.CampaignBuyAuctionInfo;
import AdAuctionApp.Cache.Central.CentralCacheManager;
import AdAuctionApp.Cache.Central.ChannelDaypartView;
import AdAuctionApp.Cache.Central.ChannelSchedule;
import AdAuctionApp.Cache.Central.CriteriaCheckerCache;
import AdAuctionApp.Cache.Central.DaypartSet;
import AdAuctionApp.Cache.Central.GridUtils;
import AdAuctionApp.Cache.Central.MarketplaceDisplay;
import AdAuctionApp.Cache.Central.OrgZoneChannelHour;
import AdAuctionApp.Cache.Central.PlacementAttribute;
import AdAuctionApp.Cache.Central.ProgramView;
import AdAuctionApp.Cache.Central.DemographicCache;
import AdAuctionApp.Cache.Central.Spot;
import AdAuctionApp.Cache.Central.SpotScheduleCache;
import AdAuctionApp.Core.AdAuctionAppException;
import AdAuctionApp.Core.AuctionTally;
import AdAuctionApp.Core.DateRange;
import AdAuctionApp.Core.DayOfWeek;
import AdAuctionApp.Core.Money;
import AdAuctionApp.Creative.MarketplaceSpotVideo;
import AdAuctionApp.Creative.MarketplaceSpotVideoCrit;
import AdAuctionApp.Creative.SpotVideo;
import AdAuctionApp.Creative.SpotVideoCrit;
import AdAuctionApp.Organization.Geography;
import AdAuctionApp.Organization.Marketplace;
import AdAuctionApp.Organization.NationalChannel;
import AdAuctionApp.Organization.Organization;
import AdAuctionApp.Organization.Site;
import AdAuctionApp.Organization.Central.CentralOrgManager;
import AdAuctionApp.Organization.Organization.OType;
import AdAuctionApp.Server.CentralAdAuctionAppServer;
import AdAuctionApp.Task.DistributedRequestCompleteCallbackIFace;
import AdAuctionApp.Task.DistributedTaskCoordinator;

/**
 * The AdBuy Master Grid aggregates accumulated information 
 * about available spots for an Ad Buy across all day parts for a date range
 * over a generic 24-hour network broadcast period for a list of network
 * channels.  Intended to act as a 'model' for a web interface.
 * Conceptually, the grid is a table of Channel rows and Daypart columns.
 * 
 * @author sgilbane
 */
public class MasterGrid
implements AuctionClient
{   
   /**
    * Constructor.
    * @param spotCache SpotScheduleCache that provides spot & bid data.
    * @param buy CampaignBuy to model.
    * @param adBuyId Ad Buy ID for this grid.
    */
   public MasterGrid(SpotScheduleCache spotCache, CampaignBuy buy)
   {
      this(spotCache, buy, false);
   }
   
   /**
    * Constructor with proposal flag.
    * @param spotCache SpotScheduleCache that provides spot & bid data.
    * @param buy CampaignBuy to model.
    * @param isProposal If true, this Grid will run its auctions suitable for proposals.
    */
   public MasterGrid(SpotScheduleCache spotCache, CampaignBuy buy, boolean isProposal)
   {
      this(spotCache, buy, isProposal, false);
   }
   
   /**
    * Constructor with proposal and standalone flags.
    * @param spotCache SpotScheduleCache that provides spot & bid data.
    * @param buy CampaignBuy to model.  Must not be null.
    * @param isProposal If true, this Grid will run its auctions suitable for proposals.
    * @param isStandalone If true, this Grid will be used standalone, and 
    *            No AdAuctionAppServer facilities will be used.
    */
   public MasterGrid(SpotScheduleCache spotCache, CampaignBuy buy, boolean isProposal,
         boolean isStandalone)
   {
      // Config info
      myAdBuy = buy;
      myCreationDate = new Date (System.currentTimeMillis());
      myLastAccessDate = new Date (System.currentTimeMillis());
      mySpotCache = spotCache;
      mySoup = new AdBuySoup();
      myIsProposal = isProposal;
      myIsStandaloneMode = isStandalone;
      
      myDestinationMsoIDs = new HashSet<Integer>();
      myDestinationMSOs = new HashSet<Organization>();
      myDestinationMarkets = new HashSet<Marketplace>();
      myInProgramViewIDs = new HashSet<Integer>();
      myPlacementAttrsByCreative = 
          new HashMap<Integer,Map<Integer,PlacementAttribute>>();
      myPlacementCriteriaByCreative = 
          new HashMap<Integer, Map<Integer, Collection<CriteriaCheckerIFace>>>();
      myChannelsByOrgMap = new HashMap<Integer, List<NationalChannel>>();
      
      // Support classes
      myConstraintSet = AdConstraintSet.createDefaultSet();
      myDateRange = new DateRange();
      myAuctionDateRange = DateRange.getForeverInstance();
      myAuctionToggles = new AuctionViewToggles(false, false, false, false);
      myProgressTracker = new ProgressTracker();
      myListeners = new PropertyChangeSupport(this);
      myOperationNotifier = new AdBuyNotifier();
      myLastMultipliers = new HashMap<OrgZoneChannelHour,Integer>();
      myProgramViews = new ArrayList<ProgramView>(0);
      
      // program Collections
      myProgramsByCategory = new TreeMap<String, List<ProgramView>>();
      myProgramsByTitle = new TreeMap<String, List<ProgramView>>();
      myProgramsByDayOfYear = new TreeMap<Integer, List<ProgramView>>();
      myProgramsByDayOfWeek = new TreeMap<Integer, List<ProgramView>>();
      myProgramsByTime = new TreeMap<Integer, List<ProgramView>>();
      myProgramsByMarket = new TreeMap<String, List<ProgramView>>();
      myProgramsByDaypart = new TreeMap<String, List<ProgramView>>();
      myProgramsSortedByImpressions = new TreeMap<Integer, List<ProgramView>>();
      
      if (myAdTargetMgr != null)
      {
          // Register ourself for notifications of Ad Target changes by the UI.
          myAdTargetMgr.registerForOperationNotification(myOperationNotifier);
          myAuctionMgr.registerForOperationNotification (myOperationNotifier); 
      }
      // Note: recalculateGeography creates an Auctioneer.
      recalculateGeography();
   }
   
   /**
    * Get the ID of this grid.
    * @return Unique ID of this grid.
    */
   public int getGridID()
   {
      return myGridID;
   }
   
   /**
    * Set the unique ID of this grid.
    * @param gridID
    */
   public void setGridID(int gridID)
   {
      myGridID = gridID;
   }
   
   /**
    * @return The Ad Buy ID associated with this grid.
    */
   public int adBuyId()
   {
	  return myAdBuy.getId();
   }
   
   /**
    * Get this grid's <code>CampaignBuy</code>.  If null, we attempt to
    * fetch the object from the server.
    * We cache the <code>CampaignBuy</code> object associated with a grid
    * so clients don't have to.  It currently is not used by the grid itself.
    * @return <code>CampaignBuy</code> associated with this grid, or null
    *     if not yet set.
    */
   public CampaignBuy adBuy()
   { 
      return myAdBuy;
   }
   
   /**
    * Create our Ad Buy Information for auctioning, using our current
    * target/total numbers for our campaign buy's baseline efficiency.
    * Note that the useAuctionWinsForBaselineEff() flag determines
    * whether we include all avails or just current winners in
    * determining the buy's baseline efficiency.
    * @return CampaignBuyAuctionInfo on client, or null if the Ad Buy
    * hasn't yet been set.
    */
   public CampaignBuyAuctionInfo auctionInfo()
   {
      CampaignBuy adBuy = adBuy();
      if (adBuy == null)
         return null;

      float baselineEffic = getGridBaselineEfficiencyPct();
      CampaignBuyAuctionInfo ci = 
          CampaignBuyAuctionInfo.createFromCampaignBuy(adBuy, baselineEffic);
      myLastAuctionBaselineEff = baselineEffic;
      
      // Copy any placement attributes, creative org info, and channel bundling data 
      // from the grid, not the CampaignBuy.
      copyCreativePlacementAttributes(ci);
      copyOrgChannelData(ci);
      copyCreativeOrgInfo(ci);

      return ci;
   }
   
   /**
    * Get the effective baseline efficiency for the AdBuy in this 
    * grid's context.  Takes into account view toggles, whether the
    * buy is active, and whether there's been an auction.
    * @return Baseline efficiency (0 - 100%);
    */
   public float getGridBaselineEfficiencyPct()
   {
       float baselineEff = 0;
       if (!constraints().isSet(IN_AUDIENCE))
       {
          // If we are not viewing target audience, we consider ALL views
          // to be in our target, and so effectively have 100% efficiency.
          baselineEff = 100;
       }
       else if (myAdBuy.isActiveState())
       {
           // If this buy is active, always use the stored baseline efficiency.
           baselineEff = myAdBuy.getBaselineEfficiency();
       }
       else if (!myAdBuy.hasSubscriberBehavior())
       {
           // No subscriber behavior implies a 100% baseline efficiency.
           baselineEff = 100;
       }
       else
       {
          // To calculate baseline efficiency for this buy, only look
          // at the grid's current auction winners (from a previous auction)
          // if we are calculating the buy's baseline efficiency.
          baselineEff = calculateLastAuctionGridEfficiency(useAuctionWinsForBaselineEff());
       }
       return baselineEff;
   }
   
   /**
    * Get the average efficiency of the bids in this buy's soup.
    * @param useWinsOnly If true, only count wins from the previous
    *   auction.
    * @return Average grid bid efficiency.
    */
   private float calculateLastAuctionGridEfficiency(boolean useWinsOnly)
   {
       AdConstraintSet cset = constraints();
       if (!useWinsOnly)
       {
           cset = cset.minusConstraint(AUCTION_WIN);
       }
       long target = sumTargetViews(cset);
       long total = sumTotalViews(cset, ImpressionType.DIGITAL_ONLY);
       return GridUtils.calculateEfficiency(total, target);
   }
   
   /***************************************************************
    * D A T E   S E C T I O N
    ***************************************************************/
   /**
    * @return The start date (in UTC time) associated with this Ad Buy.
    */
   public Calendar startDate()
   {
      return myDateRange.startDate();
   }
   
   /**
    * @return The end date (in UTC time) associated with this Ad Buy.
    */
   public Calendar endDate()
   {
      return myDateRange.endDate();
   }
   
   /**
    * Get the date and time this Grid was created.
    * @return Date that this object's constructor was called.
    */
   public Date createdDate()
   {
      return myCreationDate;
   }
   
   
   /**
    * Get the date and time this Grid was last accessed.
    * @return Date that this object was last accessed by
    * calling <code>setLastAccessDate</date>
    */
   public Date lastAccessDate()
   {
      return myLastAccessDate;
   }
   
   /**
    * Mark this Grid as having been accessed.  Useful for
    * tracking Grids that have been orphaned by the UI.
    */
   public void setLastAccessDate()
   {
      myLastAccessDate = new Date (System.currentTimeMillis());
   }

   /**
    * Change the date range for this Ad Buy.
    * We recreate the soup to force DB to recalculate its numbers.
    * No auction is done as part of soup creation.
    * @param startUtc Start date of ad buy (inclusive) in UTC time.
    * @param endUtc End date of ad buy (inclusive) in UTC time.
    */
   public void setDateRange(Calendar startUtc, 
         Calendar endUtc) 
   {
      setDateRange(startUtc, endUtc, auctionConstraints());
   }
   
   /**
    * Change the date range for this Ad Buy.
    * We recreate the soup to force DB to recalculate its numbers.
    * An auction may be done as part of soup creation, depending on toggle values.
    * @param startUtc Start date of ad buy (inclusive) in UTC time.
    * @param endUtc End date of ad buy (inclusive) in UTC time.
    * @param auctionToggleInProgram In soup creation auction, check for avails being in program.
    * @param auctionToggleUseAudience In soup creation auction, check for avails being in target audience.
    * @param auctionToggleCheckPlacement In soup creation auction, check for avails within placement.
    * @param auctionToggleCheckBudget In soup creation auction, check for avails being within budget.
    */
   public void setDateRange(Calendar startUtc, 
         Calendar endUtc, 
         boolean auctionToggleInProgram,
         boolean auctionToggleUseAudience,
         boolean auctionToggleCheckPlacement,
         boolean auctionToggleCheckBudget) 
   {
      AuctionViewToggles toggles = new AuctionViewToggles(
            auctionToggleInProgram,
            auctionToggleUseAudience,
            auctionToggleCheckPlacement,
            auctionToggleCheckBudget);
      
      setDateRange(startUtc, endUtc, toggles);
   }
   
   /**
    * Change the date range for this Ad Buy.
    * We recreate the soup to force DB to recalculate its numbers.
    * Note that the auction date range is independent from this range.
    * @param startUtc Start date of ad buy (inclusive) in UTC time.
    * @param endUtc End date of ad buy (inclusive) in UTC time.
    * @param toggles Set of flags to use in auction as part of soup creation.  If null,
    *        only calculate program and estimate reach values during soup creation.
    */
   private synchronized void setDateRange(Calendar startUtc, Calendar endUtc, AuctionViewToggles toggles)
   {
      // Sanity check the date range
      if (startUtc == null || endUtc == null)
      {
         throw new IllegalArgumentException("MasterGrid.setDateRange: Null date range!");
      }
      // Make sure normalized end date is same or after start date.
      normalizeDate(startUtc);
      normalizeDate(endUtc);
      if (endUtc.before(startUtc))
      {
         throw new IllegalArgumentException("MasterGrid.setDateRange: Inverted date range");
      }
      debugMsg ("setDateRange()",
              GridUtils.formatFullDate(startUtc) + " - " + 
              GridUtils.formatFullDate(endUtc) + "(Day " + 
              GridUtils.toDayOfYear(startUtc) + " to " +
              GridUtils.toDayOfYear(endUtc) + ")");
      
      // At least guarentee atomic update for the two.
      DateRange dr = new DateRange(startUtc, endUtc);
      myDateRange = dr;
      setAuctionDateRange(dr);
       
      createSoup(toggles);
      fireGridChanged();
   }
   
   /**
    * Guarentee that comparisons of start/end dates are on day boundaries.
    * @param cal Date to normalize.
    */
   private void normalizeDate(Calendar cal)
   {
      cal.set(Calendar.HOUR_OF_DAY, 0);
      cal.set(Calendar.MINUTE, 0);
      cal.set(Calendar.SECOND, 0);
      cal.set(Calendar.MILLISECOND, 0);
      cal.getTime().getTime(); // Updates Calendar internals.
   }
   
   /**
    * Check if the given ProgramView instance is in the date range for this grid.
    * @param pv ProgramView to check.
    * @return true if this ProgramView is in the date range for this grid.
    */
   public boolean isInDateRange(ProgramView pv)
   {
      return myDateRange.isInDateRange(pv.gridDayIndex());
   }
   
   /***************************************************************
    * O R G  /  G E O  /  M A R K E T S / C H A N N E L S
    ***************************************************************/
   /**
    * Get the set of Organizations targeted
    * by the current Ad Buy.
    * @return Set of Organizations targeted by this Ad Buy.
    */
   public Set<Organization> getTargetOrganizations()
   {
      return myDestinationMSOs;
   }
   
   /**
    * Set the set of Organizations targeted
    * by the current Ad Buy.  Used typically for unit testing.
    * @return Set of Organizations targeted by this Ad Buy.
    */
   public void setTargetOrganizations(Collection<Organization> orgs)
   {
       myDestinationMSOs.clear();
       myDestinationMSOs.addAll(orgs);
       for (Organization o : orgs)
       {
           myDestinationMsoIDs.add(o.getID());
       }
   }
   
   /**
    * Get the set of IDs for the (MSO) organizations targeted
    * by this Ad Buy.
    * @return Set of MSO Organization IDs.
    */
   public Set<Integer> getTargetOrganizationIDs()
   {  
      return myDestinationMsoIDs;
   }
   
   /**
    * Add the organization/channels for this buy.
    * @param orgID
    * @param chanList
    */
   public void addOrgChannels(int orgID, List<NationalChannel> chanList)
   {
       myChannelsByOrgMap.put(orgID, chanList);   
   }
   
   /**
    * Rebuild our Grid's notion of the Ad Buy's geography,
    * using the current Ad Buy.  Should be called
    * whenever the Ad Buy or geography may have changed.
    * Causes the Auctioneer to also be reinstantiated, since
    * the auction pool will have possibly changed.
    */
   private void recalculateGeography()
   {
      recalculateTargetMSOs();
      recalculateTargetOrganizationIDs();
      recalculateAttributeData(); // depends on recalculateTargetOrganizationIDs
      synchronized(myAuctionLock)
      {
    	  // make sure any ongoing auctions are done before replacing this auctioneer.
          myAuctioneer = new Auctioneer(mySpotCache, this, myIsProposal);
      }
   }
   
   /**
    * Recalculate our internal list of MSOs and markets targeted
    * by the current Ad Buy.
    */
   private void recalculateTargetMSOs()
   {      
      // No Ad Buy set - return empty list.
      CampaignBuy adBuy = adBuy();
      if (adBuy == null)
      {
         // Should be an error?
         return;
      }
      // Standalone mode - no managers, no MSOs.  Don't change Org info.
      if (isStandaloneMode())
      {
         return;
      }
      
      myDestinationMSOs.clear();
      myDestinationMarkets.clear();
      myChannelsByOrgMap.clear();
      
      CentralOrgManager orgMgr = CentralAdAuctionAppServer.centralInstance().centralOrgMgr();
      TargetArea target_area = adBuy.getTargetArea();
      int adbuyOrgID = adBuy.getOrganizationId();
      Organization adbuyOrg = orgMgr.getOrganization(adbuyOrgID);
      List<NationalChannel> chanList = null;
      
      // If the Ad Buy org is an MSO, register it and its market
      // as our only destination, and we're done.  Note: we have
      // to do this separately because org.getRelatedOrganizations()
      // does not return itself as an MSO!
      if (adbuyOrg.getType() == OType.HC_MSO)
      {
         myDestinationMSOs.add(adbuyOrg);
         Set<Marketplace> marketplaces = adbuyOrg.getMarketplaces();
         myDestinationMarkets.addAll(marketplaces);
         chanList = orgMgr.getNationalChannelsForOrg(adbuyOrgID);
         addOrgChannels(adbuyOrg.getID(), chanList);
         return;
      }
      
      // The Ad Buy org is an ASO.  Find its related MSOs and markets.
      List<Organization> relatedMSOs = adbuyOrg.getRelatedOrganizations();
      for (TargetAreaGeography tg : target_area.getGeographies())
      {
         Geography g = tg.getGeography();
         Set<Marketplace> markets = g.getMarketplaces();
         for (Marketplace mp : markets)
         {
            Set<Organization> related_orgs = mp.getRelatedOrgs();
            for (Organization org : relatedMSOs)
            {
               if (related_orgs.contains(org))
               {
                  // If this market's MSO is related to our Ad Buy's ASO,
                  // add the MSO and its parent market to our destination lists,
                  // and its channels to those for which we're tracking bundling.
                  // Note we do NOT include previous spending for bunding for grid!
                  myDestinationMSOs.add(org);
                  myDestinationMarkets.add(mp);
                  chanList = orgMgr.getNationalChannelsForOrg(org.getID());
                  addOrgChannels(org.getID(), chanList);
               }
            }
         }
      }
   }

   
   /**
    * Rebuild the set of IDs for the organizations targeted
    * by the current Ad Buy, using the Target MSO list.
    */
   private void recalculateTargetOrganizationIDs()
   {
      if (isStandaloneMode())
      {
         // Standalone (test) mode.
         CampaignBuy adBuy = adBuy();
         // If we haven't set the orgs explicitly, use the OrgID of the AdBuy.
         if (myDestinationMsoIDs.isEmpty())
         {
             myDestinationMsoIDs.add(adBuy.getOrganizationId());
         }
         return;
      }
      
      myDestinationMsoIDs.clear();
      for (Organization org : myDestinationMSOs)
      {
         myDestinationMsoIDs.add(org.getID());
      }
   }
   
   /**
    * Copy channel info from the grid to a CampaignBuyAuctionInfo
    * for auctioning.  Use the info configured when the grid was
    * loaded.  Note no current spending is copied.
    * @param cbai CampaignBuyAuctionInfo being copied to.
    */
   private void copyOrgChannelData(CampaignBuyAuctionInfo cbai)
   {
       for (Organization org : myDestinationMSOs)
       {
           List<NationalChannel> chanList = myChannelsByOrgMap.get(org.getID());
           cbai.addOrgChannels(org, chanList);
       }
   }
   
   /**
    * Recalculates placement attribute and criteria data. The AdBuySoup will use this data along with attribute 
    * information derived from the spot to possibly filter the soup for placement constraints.
    * Note: A creative (SpotVideo) may be associated with multiple market spot videos. For efficiency in the 
    * AdBuySoup creation, the placement attributes for the video and market spot video will be combined (attributes
    * indices do not overlap by design). Similarly the placement criteria for the spot video, market spot video and
    * the organization will be combined. These combined attributes and criteria will be held in a map, keyed by the
    * mso organization ID. With the addition of creative rotation, an ad buy (aka media buy) can have multiple 
    * creatives assigned. Each map of combined attributes and criteria will be further held in a map keyed by 
    * creative ID. 
    */
   private void recalculateAttributeData()
   {
       // Standalone mode - no managers to query
       if (isStandaloneMode())
         return;
       
       myMktSpotVideosByCreativeIDMap.clear();

      // Initialize structure for placement attributes.
      // The inner map holds all placement attributes (if any) for the combined SpotVideo and MarketplaceSpotVideo, 
      // indexed by the destination MSO org Id. This map will then be held in an outer map indexed by creative ID. 
      Map<Integer, Map<Integer, PlacementAttribute>> placementAttrsByCreative =
         new HashMap<Integer, Map<Integer, PlacementAttribute>>();   

      // Initialize structure for placement criteria.
      // The inner map holds all placement criteria (if any) for the combined SpotVideo, MarketplaceSpotVideo and 
      // destination MSO, indexed by the destination MSO org Id. This map will then be held in an outer map indexed 
      // by creative ID.
      Map<Integer, Map<Integer, Collection<CriteriaCheckerIFace>>> 
      placementCriteriaByCreative = new HashMap<Integer, Map<Integer, Collection<CriteriaCheckerIFace>>>();
    
      CriteriaCheckerCache critCache = 
         (CriteriaCheckerCache)myCentralServer.centralCacheMgr().getCache(CacheType.CRITERIA_CHECKER);
      try
      {
          // Get size and defaults for placement attribute indexed arrays
          recalculateAttributeSizeData();

          // Check if campaign buy has a creative assigned. If it does, there
          // should be a SpotVideo and corresponding MarketplaceSpotVideo for 
          // every destination MSO that the advertiser is associated with
          Collection<SpotVideo> creatives = adBuy().getSpotVideos();

          for (SpotVideo creative : creatives)
          {
              int creativeId = creative.getId();

              // Map to hold combined placement attributes for this creative
              Map<Integer, PlacementAttribute> placementAttrsByOrg = 
                  new HashMap<Integer, PlacementAttribute>();

              // Map to hold combined placement criteria for this creative
              Map<Integer, Collection<CriteriaCheckerIFace>> placementCriteriaByOrg = 
                  initializePlacementCriteriaByOrg(critCache);

              // Get Spot Video placement attributes
              PlacementAttribute spotVideoAttrs = getSpotVideoAttrs(creativeId);

              // get marketplace spot videos 
              MarketplaceSpotVideo[] msvs = getMarketplaceSpotVideos(creativeId);
              // Save them for auction purposes.
              if (msvs != null)
              {
                  myMktSpotVideosByCreativeIDMap.put(creativeId, msvs);
              }
              for (MarketplaceSpotVideo msv : msvs)
              {
                  // Get the marketplace spot video attributes, combine with spot video attributes and save combination 
                  // indexed by MSO org Id
                  PlacementAttribute msvAttrs = getMarketplaceSpotVideoAttrs(msv);
                  msvAttrs.merge(spotVideoAttrs);
                  int msoId = msv.getDestinationOrgId();
                  placementAttrsByOrg.put(msoId, msvAttrs);

                  // Add SpotVideo and MarketplaceSpotVideo criteria to those
                  // for the MSO org
                  Collection<CriteriaCheckerIFace> orgCrit = placementCriteriaByOrg.get(msoId);
                  if (orgCrit == null)
                  {
                      // We don't have this org.  Shouldn't happen, but continue.
                      continue;
                  }
                  orgCrit.addAll(critCache.getCreativePlacementCriteriaForSpotVideo(creativeId));
                  orgCrit.addAll(critCache.getCreativePlacementCriteriaForMarketplaceSpotVideo(
                          msv.getCreativeOrgId()));
                  if (orgCrit.size() == 0)
                  {
                      // Done for efficiency in the AdBuySoup
                      placementCriteriaByOrg.put(msoId, null);
                  }

              } // for each mktplace spot video.
              // save the combined atttibutes and criteria in map indexed by creative ID
              placementAttrsByCreative.put(creativeId, placementAttrsByOrg);
              placementCriteriaByCreative.put(creativeId, placementCriteriaByOrg);
          } // for each creative
      }
      catch (SQLException e)
      {
         errorMsg("recalculateAttributeData()", 
                  "SQL error getting attributes of creatives.", e);
      }
      catch (AdAuctionAppException e)
      {
         errorMsg("recalculateAttributeData()", 
                  "getting attributes of creatives failed.", e);
      }
      myPlacementAttrsByCreative = placementAttrsByCreative;
      myPlacementCriteriaByCreative = placementCriteriaByCreative;
      // Get mapping of spot schedule attributes to placement constraint attrs
      mySpotAttrMaps = myAdTargetMgr.getSpotAttrMaps();
   }
   
   /**
    * Initialize placement criteria map to those criteria defined for the org.
    * @param critCache Criteria cache
    * @return a Map keyed by mso org ID and values initialized to criteria defined for the org.
    */
   private Map<Integer, Collection<CriteriaCheckerIFace>> initializePlacementCriteriaByOrg(
         CriteriaCheckerCache critCache
      )
   {
      Map<Integer, Collection<CriteriaCheckerIFace>> placementCriteriaByOrg = 
         new HashMap<Integer, Collection<CriteriaCheckerIFace>>();
   
      // Get all target MSO's and initialize placement criteria with org placement criteria
      for (Integer msoId : getTargetOrganizationIDs())
      {
         // Initialize the criteria with those for the destination MSO
         // org. This will set an empty list if none were defined.
         List<CriteriaCheckerIFace> crits = new ArrayList<CriteriaCheckerIFace>
           (critCache.getCreativePlacementCriteriaForOrganization(msoId));
         placementCriteriaByOrg.put(msoId, crits); 
      }
      return placementCriteriaByOrg;
   }

   /**
    * Get the placement attributes for a spot video
    * @param creativeId the creative id assigned to the spot video
    * @return the placement attributes assigned to the spot video, or an empty attribute if none
    * @throws AdAuctionAppException
    */
   private PlacementAttribute getSpotVideoAttrs(int creativeId)
      throws AdAuctionAppException
   {
      // get spot video with attributes loaded
      SpotVideoCrit svc = new SpotVideoCrit(false, false, false, 
              true, false);
      svc.addById(creativeId);
      SpotVideo sv = myCentralServer.centralCreativeMgr().getSpotVideo(svc);
      // Get placement indexed attributes for just the SpotVideo 
      // (defaults if none specified).
      PlacementAttribute spotVideoAttr = PlacementAttribute.createFromAssignedAttributes(
         myBooleanAttributesCount, 
         myDefaultIntegerAttributes, 
         myDefaultStringAttributes, 
         sv.getAssignedAttributes());

      if (spotVideoAttr == null)
      {                  
          spotVideoAttr = new PlacementAttribute(
             myBooleanAttributesCount,                 
             myDefaultIntegerAttributes,
             myDefaultStringAttributes);
      }
      return spotVideoAttr;
   }
   
   /**
    * Get the marketplace spot videos associated with a creative
    * @param creativeId the creative Id
    * @return An array of marketplace spot videos; empty array if none
    * @throws AdAuctionAppException
    */
   private MarketplaceSpotVideo[] getMarketplaceSpotVideos(int creativeId)
      throws AdAuctionAppException
   {
      // Get marketplace spot videos (make sure it gets the attributes)
      MarketplaceSpotVideoCrit msvc = 
         new MarketplaceSpotVideoCrit(false, false, false, true, false);
     msvc.addByCreativeID(creativeId);
     msvc.addByMarketplaceOrgs(myDestinationMsoIDs.toArray(new Integer[myDestinationMsoIDs.size()]));

     return myCentralServer.centralCreativeMgr().getMarketplaceSpotVideos(msvc);
   }
   
   /**
    * Get the placement attributes for a marketplace spot video
    * @param msv the marketplace spot video object
    * @return the placement attributes assigned to the marketplace spot video, or an empty attribute if none
    */
   private PlacementAttribute getMarketplaceSpotVideoAttrs(MarketplaceSpotVideo msv)
   {
      // get placement attributes (if any) for MarketplaceSpotVideo
      // and merge with SpotVideo attributes.
      PlacementAttribute msvAttr = PlacementAttribute.createFromAssignedAttributes(
         myBooleanAttributesCount, 
         myDefaultIntegerAttributes, 
         myDefaultStringAttributes, 
         msv.getAssignedAttributes());
      if (msvAttr == null)
      {
         msvAttr = new PlacementAttribute(
            myBooleanAttributesCount, 
            myDefaultIntegerAttributes, 
            myDefaultStringAttributes);
      }
      return msvAttr;
   }
   
   /**
    * Recalculates the size (and default values) of each array in the
    * PlacementAttribute indexed attribute. 
    * @throws SQLException
    * @throws AdAuctionAppException
    */
   private void recalculateAttributeSizeData()
      throws SQLException, AdAuctionAppException
   {
      CentralAdAuctionAppServer central_server = 
         CentralAdAuctionAppServer.centralInstance();
      CentralCacheManager cacheMgr = central_server.centralCacheMgr();
      
      myBooleanAttributesCount = 
         cacheMgr.getAttributeCategoryDataTypeCount(
            AttributeCategoryType.PLACEMENT_CONSTRAINT, 
            AttributeDataType.BOOLEAN);
      
      myDefaultIntegerAttributes = 
         cacheMgr.getDefaultIntegerAttributeValues(
            AttributeCategoryType.PLACEMENT_CONSTRAINT, 
            -1, // don't know the size
            PlacementAttribute.DEFAULT_VALUE_FOR_NULL_INTEGER
         );
      
      myDefaultStringAttributes = 
         cacheMgr.getDefaultStringAttributeValues(
            AttributeCategoryType.PLACEMENT_CONSTRAINT, 
            -1,   // don't know the size
            PlacementAttribute.DEFAULT_VALUE_FOR_NULL_STRING
         );
   }
   
   /**
    * Gets a map of initial placement attributes objects for each
    * SpotVideo that are associated with this campaign buy. 
    * The returned map is keyed by the Creative ID of the SpotVideo,
    * and each entry in the map is itself a Map of destination-MSO-Org-ID 
    * to combined placement attributes for the SpotVideo and 
    * MarketplaceSpotVideo for that destination MSO.
    * @return map of placement attribute maps, empty if there are none.
    */
   public Map<Integer, Map<Integer, PlacementAttribute>>
          getPlacementAttributesByCreative()
   {
      return myPlacementAttrsByCreative;
   }
   
   /**
    * Copy the placement attributes for each creative into a
    * CampaignBuyAuctionInfo, in preparation to be used in simulated
    * auctioning.
    * @param cinfo CampaignBuyAuctionInfo to update.
    */
   public void copyCreativePlacementAttributes(CampaignBuyAuctionInfo cinfo)
   {
       for (Integer crID : myPlacementAttrsByCreative.keySet())
       {
           Map<Integer, PlacementAttribute> pattrMap = myPlacementAttrsByCreative.get(crID);
           for (Integer orgID : pattrMap.keySet())
           {
               PlacementAttribute pattr = pattrMap.get(orgID);
               cinfo.setPlacementAttrsForCreativeOrg(crID, orgID, pattr);
           }
       }
   }
   
   /**
    * Copy the MarketplaceSpotVideo data from creatives on this buy into a 
    * CampaignBuyAuctionInfo for auction purposes.
    * @param cinfo CampaignBuyAuctionInfo to copy into.
    */
   public void copyCreativeOrgInfo(CampaignBuyAuctionInfo cinfo)
   {
       for (Integer crID : myMktSpotVideosByCreativeIDMap.keySet())
       {
           MarketplaceSpotVideo[] msvArr = myMktSpotVideosByCreativeIDMap.get(crID);
           for (MarketplaceSpotVideo msv : msvArr)
           {
               // Copy MSV info as if creative had been propagated and approved
               // for simulated auctions.
               cinfo.setSpotIdForCreativeOrg(msv.getSpotId(), 
                       msv.getCreativeId(),
                       msv.getDestinationOrgId(),
                       true,        // As if has been propagated.
                       true);       // As if has been approved.
               cinfo.setCreativeOrgInfo(msv);
           }
       }
   }
   
   /**
    * Gets a map of maps of placement criteria objects. The criteria consist of those
    * defined for the SpotVideo and MarketplaceSpotVideos (if defined) and for
    * the marketplace organizations MSO. 
    * The map is keyed by creative ID, and contains entries that are
    * maps of criteria sets, keyed by destination MSO ID. 
    * @return map of placement criteria, empty if there are none
    */
   public Map<Integer, Map<Integer, Collection<CriteriaCheckerIFace>>> 
          getPlacementCriteriaByCreative()
   {
      return myPlacementCriteriaByCreative;
   }
   
   /**
    * Get the number of boolean attributes defined for placement constraints. 
    * @return number of boolean attributes defined for placement constraints.
    */
   public int getBooleanAttributesCount()
   {
      return myBooleanAttributesCount;
   }
   
   /**
    * Get the default int values for placement constraint attributes. 
    * @return default int values for placement constraint attributes.
    */
   public int[] getDefaultIntegerAttributes()
   {
      return myDefaultIntegerAttributes;
   }
   
   /**
    * Get the default String values for placement constraint attributes. 
    * @return default String values for placement constraint attributes.
    */
   public String[] getDefaultStringAttributes()
   {
      return myDefaultStringAttributes;
   }
   
   /**
    * Get the mapping between spot schedule indexed attributes placement 
    * constraint indexed attributes.  
    * @return map of attribute indexes by attributes data type
    */
   public Map<AttributeDataType, int[]> getSpotAttrMaps()
   {
      return mySpotAttrMaps;
   }
   
   /**
    * Get the list of local sites for Organizations targeted 
    * by this Ad Buy.
    * @return List of local Sites.
    */
   public List<Site> getTargetSites()
   {
      List<Site> targetSites = new LinkedList<Site>();
      CentralOrgManager org_mgr = CentralAdAuctionAppServer.centralInstance().centralOrgMgr();
      
      Set<Organization> target_orgs = getTargetOrganizations();
      for (Organization org : target_orgs)
      {
         Site s = org_mgr.getLocalSiteForOrg(org);
         if (s != null)
         {
             targetSites.add(s);
         }
      }
      return targetSites;
   }
   
   /**
    * Get the set of Organization IDs that this Ad Buy targets.
    * @return Set of destination Org IDs.
    */
   public Set<Integer> getDestinationOrgIDs()
   {
      return myDestinationMsoIDs;
   }
   
   /**
    * Return true if orgID is in our list of destination organizations.
    * @param orgID
    * @return true if in list.
    */
   public boolean isDestinationOrganization(int orgID)
   {
      return myDestinationMsoIDs.contains(orgID);
   }

   /**
    * Get list of markets that have been configured that are
    * relevant to this Ad Buy.
    * @return List of MarkeplaceDisplays.
    */
   public List<MarketplaceDisplay> getTargetMarkets()
   {
      return mySpotCache.getMarketplaceDisplays(myDestinationMarkets);
   }
   
   /**
    * Reset the target multiplier to 100% (target = total views).
    * This would be the case if an AdBuy has no defined target
    * audience.
    */
   public void setTargetMultiplierTo100()
   {
      mySoup.setAllSpotTargetViewsToTotal();
   }
   
   /**
    * Set all the target view count on all spots under an
    * organization to zero.  This typically is called when
    * multipliers from an organization are unknown.
    * @param orgID Organization ID 
    */
   public synchronized void setTargetMultiplierToZero(int orgID)
   {
      mySoup.clearSpotTargetViews(orgID);
   }
   
   /**
    * Set all spots and all programs to be IN_PROGRAM.
    * Populate set of IN_PROGRAM ProgramView IDs.
    */
   public void includeAllPrograms()
   {
      mySoup.setInProgramAll(true);
      myInProgramViewIDs = mySoup.getInProgramViewIDs();
   }
   
   /**
    * @return Our parent Spot Schedule Cache.
    */
   public SpotScheduleCache getSpotScheduleCache()
   {
      return mySpotCache;
   }
   
   /**
    * Get the list of all SpotShadow objects for our soup,
    * each spot indexed by its spot ID. Used during auctioning.
    * @return Map of SpotID to SpotShadow.
    * @see AuctionClient
    */
   public Map<Integer,SpotShadow> getSpotMap()
   {
      return mySoup.getSpotShadowMap();
   }
   
   /********************************************************************
    *         C O N S T R A I N T   M E T H O D S
    ********************************************************************/
   /**
    * Get the constraint set used as a filter on what data from 
    * the grid is included in the estimated values.
    * @return The constraint set currently set on this grid.
    */
   public AdConstraintSet constraints()
   {
      return myConstraintSet;
   }
   /**
    * View toggles to be applied to the requested auction.
    * @return AuctionViewToggles
    * @see AuctionClient
    */
   public AuctionViewToggles auctionConstraints()
   {
      return myAuctionToggles;
   }
   
   /**
    * Set the toggles to be applied to the next requested auction.
    * @param toggles AuctionViewToggles
    */
   public void setAuctionConstraints(AuctionViewToggles toggles)
   {
      myAuctionToggles = toggles;
   }
   
   /**
    * Set this grid's Ad Buy Constraints.
    * @param newSet New constraints.
    * @see AdConstraintSet
    */
   public void setConstraints(AdConstraintSet newSet)
   {
      // Any change to constraints clears progress error memory.
      myProgressTracker.clearLastError();
      // Update auction toggles if necessary
      translateConstraints(newSet);

      if (myConstraintSet.matches(newSet))
      {
         return;
      }
      myConstraintSet = newSet;
      fireGridChanged();
   }
   
   /**
    * Convenience method indicating whether toggles are showing Auction Wins.
    * @return true if View Toggles include viewing Auction Wins.
    */
   public boolean isViewingAuctionWins()
   {
       return constraints().isSet(AdConstraintSet.Constraint.AUCTION_WIN);
   }
   
   /**
    * Pass changes in AdConstraints to the AuctionToggles.
    * Only IN_PROGRAM and IN_AUDIENCE are applicable.
    * @param cset AdConstraints.
    */
   private void translateConstraints(AdConstraintSet cset)
   {
      boolean prog = cset.isSet(IN_PROGRAM);
      boolean aud = cset.isSet(IN_AUDIENCE);
      boolean budget = myAuctionToggles.useBudget;
      boolean placem = myAuctionToggles.usePlacement;
      myAuctionToggles = new AuctionViewToggles(prog, aud, placem, budget);
   }

   /********************************************************************
    *             A V A I L    S O U P   S U P P O R T 
    ********************************************************************/
   
   /**
    * Public method to recreate soup, including rebuilding the Grid's
    * understanding of the target Geography. This should be called
    * when the geography, date, day-of-week, or creative (or anything that may 
    * change the makeup of this buy's set of avails) has been changed by the user.
    * No constraints are used here; the expectation is that the UI will set toggles after.
    */
   public void recreateSoup()
   {
      recalculateGeography();
      createSoup(AuctionViewToggles.NO_CONSTRAINTS);
      fireGridChanged();
   }
   
   /**
    * Create (or recreate) soup (of spot avails).
    * Clear the Grid state and update the lastAccessed Date.
    * @param toggles The set of toggles to use in the auction
    *    to run as part of soup creation.  If null, no auction is run.
    */
   private void createSoup(AuctionViewToggles toggles)
   {  
      myAuctionToggles = toggles;
      myProgressTracker.clearLastError();
      myLastMultipliers.clear();
      myCurrentProposalInfo = null;
      myCurrentProposalAuctioneer = null;
      myLastAuctionBaselineEff = 0;
      
      setLastAccessDate();
      
      if (!isStandaloneMode())
      {
         createSoupWithEstimateReach();
      }
      else 
      {          
         // Create soup without starting Estimate Reach.
         createGridSoupFromCache();
         if (toggles.isAuction)
         {
            runAuction();
         }
      }
   }
   
   /**
    * Create soup and automatically start estimate reach, 
    * based on toggles.
    */
   private void createSoupWithEstimateReach()
   {
      debugMsg("createSoupWithEstimateReach()", " creating soup for AdBuy #" + adBuyId());

      // create soup from cache
      boolean createSoupSuccess = createGridSoupFromCache();
      //
      // If soup was successfully created,
      // start a call to estimate reach.  
      if (createSoupSuccess)
      {
         startEstimateReach();
      }
      else
      {
         errorMsg("createSoupWithEstimateReach()", "Soup creation failed.", null);
      }
   }
   
   /**
    * Disconnect ourselves from soup and tear down soup.
    * Should be called with this MasterGrid locked.
    */
   public void shutdown()
   {
      debugMsg("shutdown()", "Grid #" + adBuyId() + "-" +  getGridID() + " shutting down...");

      if (myOperationNotifier != null && !myIsStandaloneMode)
      {
         CentralAdvertisingTargetManager atmgr = 
            CentralAdAuctionAppServer.centralInstance().centralAdvertisingTargetMgr();
         atmgr.unregisterOperationNotification(myOperationNotifier);
         CentralAuctionManager auctionMgr = 
            CentralAdAuctionAppServer.centralInstance().centralAuctionMgr();
         auctionMgr.unregisterOperationNotification(myOperationNotifier);
      }
   }

   /**
    * Start a call to calculate estimate reach based on
    * the current ad buy settings and soup.
    */
   private void startEstimateReach()
   {
      //
      // Proposal grids will call the estimate reach task and synchronously wait for it.
      //
      if (myIsProposal)
      {
         return;
      }
      CentralAdvertisingTargetManager tmgr = 
         CentralAdAuctionAppServer.centralInstance().
                                 centralAdvertisingTargetMgr();

      // If we are checking budget and/or placement, 
      // we need to run an auction.
      AuctionViewToggles auctionFlags = auctionConstraints();
      CampBuyOperationDTC dtc = null;
      try
      {
         if (!auctionFlags.isAuction)
         {
            debugMsg("startEstimateReach()",
                  "Starting estimate reach (no auction) for AdBuy #" + adBuyId());
            // Estimate reach + programs only
            dtc = tmgr.performMultipleCampaignBuyOperations(adBuy(),
                    MasterGrid.this,
                  true,true,false,
                  false,false,false,false);
         }
         else
         {
            debugMsg("startEstimateReach()",
                  "Starting estimate reach with auction for AdBuy #" + adBuyId());
            // Estimate reach + programs + auction with flags as indicated.
            dtc = tmgr.performMultipleCampaignBuyOperations(adBuy(),
                    MasterGrid.this,
                  true,true,true,
                  auctionFlags.inProgram,
                  auctionFlags.inAudience,
                  auctionFlags.usePlacement,
                  auctionFlags.useBudget);
         }
      }
      catch (AdAuctionAppException aee)
      {
         errorMsg("startEstimateReach()", 
                 "estimate reach for AdBuy #" + adBuyId() +
                 " during soup creation failed.", aee);
         return;
      }
   }
   /*********************************************************************
    *   A F F I L I A T E / D A Y P A R T    A C C E S S   M E T H O D S 
    *********************************************************************/
   
   /**
    * @return The set of dayparts configured for this grid.
    */
   public DaypartSet daypartSet()
   {
      return mySpotCache.daypartSet();
   }
   
   /**
    * @return Number of cable channels represented in this grid.
    */
   public int channelCount()
   {
      return channels().size();
   }
   
   /**
    * @return List of cable channels.
    */
   public List<AdBuySoup.CableChannel> channels()
   {
      return mySoup.channels();
   }
   
   /**
    * @return the list of channel names, suitable for using as keys.
    */
   public List<String> channelNames()
   {
      List<String> affNames = new LinkedList<String>();
      for (CableChannel aff : channels())
      {
         affNames.add(aff.name());
      }
      return affNames;
   }
   
   /**
    * Return the CableChannel by index.
    * @param i Index of channel.
    * @return CableChannel at index.
    * @throws IllegalArgumentException if index is out of range.
    */
   public CableChannel getChannelByIndex(int i)
   {
      if (i >= channels().size()) 
         throw new IllegalArgumentException("Invalid channel index " + i);
      return channels().get(i);
   }
   
   /**
    * Return the CableChannel by ChannelSchedule ID, or null if unknown ID.  
    * Package access only.
    * @param id ID of channel.
    * @return ChannelSchedule of this ID.
    */
   public CableChannel getChannelById(int id)
   {
      for (CableChannel aff : channels())
      {
         if (aff.id() == id)
         {
            return aff;
         }
      }
      return null;
   }
   
   /**
    * Return the CableChannel by name, or null if unknown name.  
    * Case-insensitive.
    * @param chName Name of this channel.
    * @return CableChannel that has this name.
    */
   public CableChannel getChannelByName(String chName)
   {
      for (CableChannel chan : channels())
      {
         if (chan.name().equalsIgnoreCase(chName))
         {
            return chan;
         }
      }
      return null;
   }
   
   /**
    * Called by the estimateReach task to set the multipliers for spots.  The 
    * data format in the buffer is as follows.
    * [0]: channel ID
    * [1]: zone ID
    * [2]: hour of week index
    * [3]: percentage multiplier
    * 
    * @param orgID the local MSO organization ID
    * @param data_buffer Buffer containing muliplier information.
    * @param num_chunks number of 8-byte chunks in the buffer
    */
   public synchronized void setTargetMultipliers(int orgID, 
           ShortBuffer data_buffer, int num_chunks)
   {  
      // each spot multiplier data is 4 shorts
      int channel;
      int zoneID;
      int segment;
      int multiplier;
      
      myLastMultipliers.clear();
      for (int i = 0; i < num_chunks; i++)
      {
         // Order of get()'s is significant.
         channel = data_buffer.get();
         zoneID = data_buffer.get();
         segment = data_buffer.get();
         multiplier = data_buffer.get();
         
         OrgZoneChannelHour key = new OrgZoneChannelHour(orgID,
                 zoneID, channel, segment);
         
         // Uncomment this for multiplier debugging; commented out to save memory.
         //myLastMultipliers.put(key, multiplier);
         
         // get spots from cache and apply the multiplier
         List<Spot> spots = mySpotCache.getSpotsForOrgZoneChannelHour(key);
         for (Spot s : spots)
         {
            // If a Spot from this channel/zone/hour is in our soup, 
            // re-calculate its target view count using the multiplier.
            mySoup.calculateSpotTargetViews(s.id, multiplier);
         }
      }
      fireGridChanged();
   }
   
   /**
    * Return the total number of estimated impressions for the entire grid
    * using the current view constraints.
    * @param imprType  If DIGITAL_ONLY, use only estimates from digital settops;
    *        If ANALOG_DIGITAL, use estimates from both analog and digital settops.
    *        Digital-only totals should be used when calculating efficiency.
    *        If DEMOGRAPHIC, use estimates from Nielsen for this buy's demographic.
    * @return Total number of estimated views.
    */
   public long sumTotalViews(boolean isDigitalOnly)
   {
      return sumTotalViews(constraints(), (isDigitalOnly ? ImpressionType.DIGITAL_ONLY 
                                                         : ImpressionType.ANALOG_DIGITAL));
   }
   
   /**
    * Return the total number of estimated demographic impressions for the entire grid
    * using the current view constraints.
    * @return Total number of demographic views.
    */
   public long sumDemographicViews()
   {
       return sumTotalViews(constraints(), ImpressionType.DEMOGRAPHIC);
   }
   
   /**
    * Return the total number of estimated impressions for the entire grid.
    * @param cset Constraint set to filter with.
    * @param imprType  If DIGITAL_ONLY, use only estimates from digital settops;
    *        If ANALOG_DIGITAL, use estimates from both analog and digital settops.
    *        Digital-only totals should be used when calculating efficiency.
    *        If DEMOGRAPHIC, use estimates from Nielsen for this buy's demographic.
    * @return Total number of estimated views.
    */
   public long sumTotalViews(AdConstraintSet cset, ImpressionType imprType)
   {
      long total = 0;
      List<CableChannel> channels = mySoup.channels();
      for (CableChannel chan : channels)
      {
         List<DaypartCell> dpList = chan.daypartCells();
         for (DaypartCell cell : dpList)
         {
            total += cell.totalViews(cset, imprType);
         }
      }
      return total;
   }
   /**
    * Return the total number of estimated target impressions for the entire 
    * grid using the current view constraints.
    * @return Total number of target views.
    */
   public long sumTargetViews()
   {
      return sumTargetViews(constraints());
   }
   
   /**
    * Return the total number of estimated target impressions for the entire grid.
    * @param cset View constraints to filter with.
    * @return Total number of target views.
    */
   public long sumTargetViews(AdConstraintSet cset)
   {
      long total = 0;
      List<CableChannel> channels = mySoup.channels();
      for (CableChannel chan : channels)
      {
         List<DaypartCell> dpList = chan.daypartCells();
         for (DaypartCell cell : dpList)
         {
            total += cell.targetViews(cset);
         }
      }
      return total;
   }
  
   /**
    * Return the total number of estimated impressions for a single daypart.
    * @param dpIndex Index of Daypart in grid.
    * @param imprType  If DIGITAL_ONLY, use only estimates from digital settops;
    *        If ANALOG_DIGITAL, use estimates from both analog and digital settops.
    *        Digital-only totals should be used when calculating efficiency.
    *        If DEMOGRAPHIC, use estimates from Nielsen for this buy's demographic.
    * @return Total number of estimated views.
    */
   public long sumTotalViewsForDaypart(int dpIndex, ImpressionType imprType)
   {
      long total = 0;
      AdConstraintSet cset = constraints();
      List<CableChannel> channels = mySoup.channels();
      for (CableChannel chan : channels)
      {
         DaypartCell cell = chan.getDaypartCellByIndex(dpIndex);
         total += cell.totalViews(cset, imprType);
      }
      return total;
   }
   
   /**
    * Get the sum of target views for a ProgramView for this Ad Buy.
    * @param pView ProgramView
    * @param cset Constraint set being used to filter our view of the data.
    * @param dp Daypart to be contained by.  May be null.
    * @return Count of estimated targeted views for this program using this Ad Buy.
    */
   public long targetViewsForProgram(ProgramView pView, AdConstraintSet cset, DaypartSet.Daypart dp)
   {
      return mySoup.programTargetViews(pView, cset, dp);
   }
   
   /**
    * Get the sum of total analog+digital views for a ProgramView for this Ad Buy.
    * @param pView ProgramView
    * @param cset Constraint set being used to filter our view of the data.
    * @param dp Daypart to be contained by.  May be null.
    * @return Count of estimated total views for this program using this Ad Buy.
    */
   public long totalADViewsForProgram(ProgramView pView, AdConstraintSet cset, DaypartSet.Daypart dp)
   {
      return mySoup.programTotalViews(pView, cset, dp, ImpressionType.ANALOG_DIGITAL);
   }
   
   /**
    * Get the sum of total digital-only views for a ProgramView for this Ad Buy.
    * @param pView ProgramView
    * @param cset Constraint set being used to filter our view of the data.
    * @param dp Daypart to be contained by.  May be null.
    * @return Count of estimated total views for this program using this Ad Buy.
    */
   public long totalDigitalViewsForProgram(ProgramView pView, AdConstraintSet cset, DaypartSet.Daypart dp)
   {
      return mySoup.programTotalViews(pView, cset, dp, ImpressionType.DIGITAL_ONLY);
   }
   
   /**
    * Does this program view contain any spots that are in this grid?
    * @param pView ProgramView to test.
    * @return true if there is at least one Spot in one break in this program
    * that is in our grid soup.
    */
   public boolean programContainsAnySpots(ProgramView pView)
   {
       return mySoup.programContainsAnySpots(pView);
   }
   
   /**
    * Get the list of all program view instances for an channel.
    * Use the Constraints to filter the list, and don't include programs
    * with zero impressions.
    * @param cset Ad Constraint set used to calculate totals.
    * @param cc Cable Channel
    * @return List of a copy of all programs in the schedule.
    */
   public List<ProgramView> programViewList(CableChannel cc, AdConstraintSet cset)
   {
      List<ProgramView> plist = new LinkedList<ProgramView>();
      boolean checkInProgram = cset.isSet(AdConstraintSet.Constraint.IN_PROGRAM);
      ChannelSchedule aff = cc.channelSchedule();

      for (ProgramView prog : aff.allPrograms()) 
      {
         // check date range
         if (isInDateRange(prog) == false)
         {
            continue;
         }
         
         // If we are checking for in-program and this one's not, don't include it.
         if (checkInProgram && !isInProgram(prog.viewId()))
         {
            continue;
         }
         
         // If the program has no impressions, don't include it.
         if (totalADViewsForProgram(prog, cset, null) == 0)
         {
            continue;
         }
         plist.add(prog);
      }
      return plist;
   }  
   
   /**
    * Get a CachedProgram by ID from the cache.
    * @param id Program ID (Program Ref Number).
    * @return CachedProgram.
    */
   public CachedProgram getProgramByID(int id)
   {
      return mySpotCache.getProgramByID(id);
   }
   
   /********************************************************************
    *      D E M O G R A P H I C    S U P P O R T 
    ********************************************************************/
   
   /**
    * @return The total Nielsen viewers for our current demographic definition.
    */
   public long demoAudienceUniveralTotal()
   {
       return myDemoAudienceUniversalTotal;
   }
   
   /**
    * Re-evaluate all avails in the soup using the DemographicCalculator on our buy.
    * It will evaluate bids as 'In Demographic' for our current demographic definition
    * for the Buy (if any).
    * @see calculateDemographics
    */
   public void applyBuyDemographicsToGrid()
   {
       calculateDemographics(myAdBuy.getDemographicCalculator());
   }
   
   /**
    * Using the passed-in DemographicCalculator, re-evaluate avails in the soup
    * that evaluate to being 'In Demographic' for our current demographic definition
    * for our Buy (if any).  Also, evaluate missing data and unrated quality on
    * each avail, and set the universal totals for TRP calculations.
    * @param dc DemographicCalculator for this demographic audience.
    */
   public void calculateDemographics(DemographicCalculator dc)
   {
       if (!dc.supportsDemographics())
       {
           // If no demographics are defined, clear demo data.
           mySoup.clearAllDemographics();
           return;
       }
       boolean allowMissing = myAdBuy.allowDemoMissingData();
       boolean allowUnrated = myAdBuy.allowDemoUnratedInventory();
       mySoup.calculateAvailsInDemographic(dc, allowUnrated, allowMissing);
       myDemoAudienceUniversalTotal = dc.selectedSegmentTotals( 
               mySpotCache.getDemographicCache());
   }
   
   /**
    * Get the TRP for this buy with the current AdConstraints and demographics.
    * @return
    */
   public double getGridTrp()
   {
       long demoTotals = sumDemographicViews();
       if (myDemoAudienceUniversalTotal == 0)
       {
           return 0;
       }
       return ((double)demoTotals / myDemoAudienceUniversalTotal);
   }
   
   /**
    * @return The total of all GRP values for all bids that won for
    * this grid in the last auction.
    */
   public double getGrpTotalForGridAuctionWinners()
   {
       Set<AuctionObjectShadow> wins = currentAuctioneer().sortBidsByIndex(true, true);
       double grpTotal = 0.0;
       for (AuctionObjectShadow bid : wins)
       {
           grpTotal += bid.auctionObj.spot.grp();
       }
       return grpTotal;
   }
   
   /********************************************************************
    *      P R O G R A M    L I S T    C O L L E C T I O N S
    ********************************************************************/
   
   /**
    * Using the passed-in CriteriaChecker, refresh the list of programs
    * from our SpotScheduleCache that evaluate to being 'In Program'
    * for our current Ad Buy settings.
    * @param programChecker
    */
   public void calculatePrograms(CriteriaCheckerIFace programChecker)
   {  
      // First - clear the list of program views that are part of
      // the criteria.  This list will be refilled through the
      // calculateSpotsInProgram method.
      myInProgramViewIDs.clear();
      
      // This method will determine which spots/breaks meet the criteria.
      // It returns the set of ProgramViewIDs that contain breaks that
      // meet the criteria.  This set of IDs is needed for the program list view
      // within the grid.
      myInProgramViewIDs = mySoup.calculateSpotsInProgram(programChecker);
      fireGridChanged();
   }

   /**
    * Get a copy of all the breaks in a cell for this campaign buy.
    * @param adv Cell over which to apply to calculation.
    * @return List of breaks in this channel's daypart.
    */
   public List<BreakView> getAllBreaksInCell(ChannelDaypartView adv)
   {
      List<BreakView> breakList = new LinkedList<BreakView>();
      Set<Integer> orgIds = adv.getOrgIds();

      // loop through the organizations
      for (int oId : orgIds)
      {
         if (isDestinationOrganization(oId) == false)
         {
            continue;
         }

         List<BreakView> bvs = adv.getBreaksByOrgId(oId);
         breakList.addAll(bvs);
      }
      return breakList;
   }
   
  /** 
   * Return a map of all the breaks in a cell for this campaign buy.
   * The map 
   * @param adv Cell over which to apply to calculation.
   * @return List of breaks in this channel's daypart.
   */
  public Map<Integer, List<BreakView>> getBreaksInCellByOrg(ChannelDaypartView adv)
  {
     Map<Integer, List<BreakView>> rtnMap = new HashMap<Integer, List<BreakView>>();
     Set<Integer> orgIds = adv.getOrgIds();

     // loop through the organizations
     for (int oId : orgIds)
     {
        if (isDestinationOrganization(oId) == false)
        {
           continue;
        }

        List<BreakView> bvs = adv.getBreaksByOrgId(oId);
        rtnMap.put(oId, bvs);
     }
     return rtnMap;
  }
   
   /**
    * Add a program to the list of programs that satisfy our
    * current Program Criteria.
    * @param pv Program to add.
    */
   private void addProgram(ProgramView pv)
   {
      // get program data.  If not found use the "Unknown" program.
      CachedProgram pd = mySpotCache.getProgramByID(pv.programId());
      if (pd == null)
      {
         pd = CachedProgram.UNKNOWN_PROGRAM;
      }
      
      addProgramToStrBucket(myProgramsByCategory, pv, pd.category);
      addProgramToStrBucket(myProgramsByTitle, pv, pd.title);
      addProgramToStrBucket(myProgramsByMarket, pv, pv.programList().marketplace().name());
      addProgramToIntBucket(myProgramsByDayOfYear, pv, pv.airDayOfYear());
      addProgramToIntBucket(myProgramsByDayOfWeek, pv, pv.airDayOfWeek());
      DaypartSet.Daypart dp = this.daypartSet().byDayMinutes(pv.startMinutes());
      addProgramToStrBucket(myProgramsByDaypart, pv, dp.title());
      addProgramToIntBucket(myProgramsByTime, pv, pv.startMinutes());
   }
   
   private void addProgramsToBuckets(List<ProgramView> programs)
   {
      emptyProgramCollections();
      
      for (ProgramView pv : programs)
      {
         addProgram(pv);
      }
   }
   
   /**
    * Clear our list of programs in our program criteria.
    */
   private void emptyProgramCollections()
   {
      myProgramsByCategory.clear();
      myProgramsByTitle.clear();
      myProgramsByMarket.clear();
      myProgramsByDayOfYear.clear();
      myProgramsByDayOfWeek.clear();
      myProgramsByDaypart.clear();
      myProgramsByTime.clear();
   }
   
   /** Set the supplied program view ID in the list of program
    *  view IDs that are in program.
    *  @param progViewID The Program View ID that shall be marked as
    *  in program.
    */
   public void setIsInProgram (int progViewID)
   {
      myInProgramViewIDs.add(progViewID);
   }
   
   /**
    * Test if the given program view is in program. Valid only after we have
    * finished calculating programs.  Note ID is view ID, not prog ref number.
    * @param progViewID
    * @return true if in Program Criteria.
    */
   public boolean isInProgram(int progViewID)
   {
      return myInProgramViewIDs.contains(progViewID);
   }
   
   /**
    * Get the set of ProgramView IDs that are IN_PROGRAM.
    * May be an empty set.
    * @return Set of ProgramView IDs.
    */
   public Set<Integer> getInProgramViewIds()
   {
      return myInProgramViewIDs;
   }
   
   /**
    * Get a copy of the list of program views for a given network.
    * We get the program list directly from the channel and sort the list.
    * @param nwName Network Channel name.
    * @return List of programs, sorted by title.
    */
   public List<ProgramView> programsByNetwork(String nwName)
   {
      CableChannel cc = getChannelByName(nwName);
      AdConstraintSet cset = constraints();
      List<ProgramView> plist = programViewList(cc, cset);
      
      Collections.sort(plist, myPgComparatorByTitle);
      return  plist;
   }
   
   /**
    * Get a copy of the list of program views in a programming category (drama, etc.).
    * @param catName Category name.
    * @return List of programs
    */
   public List<ProgramView> programsByCategory(String catName)
   {
      return getProgramsFromStrBucket(myProgramsByCategory, catName, myPgComparatorByTitle);
   }
   
   /**
    * @return List of program categories that we have <code>ProgramViews</code>
    * registered against.
    */
   public List<String> programCategories()
   {
      return getBucketStrKeys(myProgramsByCategory);
   }
   
   /**
    * Get a copy of the list of program views by program title.
    * @param pTitle Program title.  There may be multiple airings of
    *        multiple episodes of the same program title.
    * @return List of program airings of this title.
    */
   public List<ProgramView> programsByTitle(String pTitle)
   {
      return getProgramsFromStrBucket(myProgramsByTitle, pTitle, myPgComparatorByTitle);
   }
   
   /**
    * @return List of program titles that we have <code>ProgramViews</code>
    * registered against.
    */
   public List<String> programTitles()
   {
      return getBucketStrKeys(myProgramsByTitle);
   }
   
   /**
    * Get a copy of the list of program views in a given daypart.
    * @param dayOfYear Day of year (Stringified Integer).
    * @return List of programs on this day.
    */
   public List<ProgramView> programsByDayOfYear(Integer dayOfYear)
   {
      return getProgramsFromIntBucket(myProgramsByDayOfYear, dayOfYear, myPgComparatorByTitle);
   }
   
   /**
    * @return List of program days that we have <code>ProgramViews</code>
    * registered against.  The keys are day-of-year values as integers.
    * @see java.util.Calendar#DAY_OF_YEAR
    */
   public List<Integer> programDays()
   {
      return getBucketIntKeys(myProgramsByDayOfYear);
   }
   
   /**
    * Get a copy of the list of program views that start in a given daypart.
    * @param daypartTitle Title of daypart.
    * @return List of programs that start in this daypart.
    */
   public List<ProgramView> programsByDaypart(String daypartTitle)
   {
      return getProgramsFromStrBucket(myProgramsByDaypart, daypartTitle, thePgComparatorByTime);
   }
   
   /**
    * Get a copy of the list of program views in a given daypart.
    * @param minuteOfDay Minute of day (Integer).
    * @return List of programs at this time of day.
    */
   public List<ProgramView> programsByTime(Integer minuteOfDay)
   {
      return getProgramsFromIntBucket(myProgramsByTime, minuteOfDay, myPgComparatorByTitle);
   }
   
   /**
    * Get a copy of the list of program views in a given day of the week.
    * @param dayOfWeek Day of Week (Integer).
    * @return List of programs at this day of week.
    * @see java.util.Calendar#DAY_OF_WEEK
    */
   public List<ProgramView> programsByDayOfWeek(Integer dayOfWeek)
   {
      return getProgramsFromIntBucket(myProgramsByDayOfWeek, dayOfWeek, myPgComparatorByTitle);
   }
   
   /**
    * @return List of program times that we have <code>ProgramViews</code>
    * registered against.  The keys returned are minutes-in-day values as Integers.
    */
   public List<Integer> programTimes()
   {
      return getBucketIntKeys(myProgramsByTime);
   }
   
   /**
    * Get a copy of the list of <code>ProgramViews</code> in a given market.
    * @param mktName Name of market (that is, MarketplaceDisplay.name() value).
    * @return List of programs in this market.
    */
   public List<ProgramView> programsByMarket(String mktName)
   {
      return getProgramsFromStrBucket(myProgramsByMarket, mktName, thePgComparatorByTime);
   }
   /**
    * @return List of program markets that we have <code>ProgramViews</code>
    * registered against.  The keys returned are market names (that is, 
    * MarketplaceDisplay.name() values).
    */
   public List<String> programMarketNames()
   {
      return getBucketStrKeys(myProgramsByMarket);
   }
   
   /**
    * Get a copy of the list of <code>ProgramViews</code> with a given impression count.
    * @param fromCountPct Percent of the list by total impressions to start from.
    * @param toCountPct Percent of the list by total impressions to end before.
    * @return List of programs with this count of total impressions.
    */
   public List<ProgramView> programsByImpressionPercent(int fromCountPct, int toCountPct)
   {
      //
      // We sort the program list by impression on first request, not as programs are added.
      // We can't order them as they are inserted by impression count, because at the time of program
      // creation, typically we have 0 impressions, and as spots are added, the impression count rises.
      // We also resort when we change view constraints, since the sorted list does not included
      // programs that are not IN_PROGRAM if that constraint is set.
      //
      if (myProgramsSortedByImpressions.size() == 0)
      {
         sortProgramsByImpressionCount();
      }

      return selectProgramListSegmentByPct(myProgramsSortedByImpressions, fromCountPct, toCountPct);
   }
   
   /**
    * Sort the list of programs by impressions.
    * Don't include 0 impression programs or programs that are not IN_PROGRAM if
    * our constraints are set to include that view.
    */
   private void sortProgramsByImpressionCount()
   {
      AdConstraintSet cset = constraints();
      boolean checkInProg = cset.isSet(AdConstraintSet.Constraint.IN_PROGRAM);
      myProgramsSortedByImpressions.clear();
      for (ProgramView pv : myProgramViews)
      {
         if (canAddProgramToList(pv, cset, checkInProg))
         {
        	int total = (int)totalADViewsForProgram(pv, cset, null);
            addProgramToIntBucket(myProgramsSortedByImpressions, pv, total);
         }
      }
   }
   
   /**
    * Get a copy of the list of <code>ProgramViews</code> in a range of efficiencies.
    * @param fromCountPct Percent of the list by efficiency to start from.
    * @param toCountPct Percent of the list by efficiency to end before.
    * @return List of <code>ProgramViews</code> with this count of total impressions.
    */
   public List<ProgramView> programsByEfficiency(int fromCountPct, int toCountPct)
   { 
      Map<Integer, List<ProgramView>> programsSortedByEff = new TreeMap<Integer, List<ProgramView>>();
      AdConstraintSet cset = constraints();
      
      // If IN_AUDIENCE view is not set, efficiency is undefined for this grid.  Return empty list.
      if (!cset.isSet(IN_AUDIENCE))
      {
         return new ArrayList<ProgramView>();
      }
      
      // Construct the list calculating efficiency 
      for (ProgramView pv : myProgramViews)
      {
    	  long total = totalDigitalViewsForProgram(pv, cset, null);
    	  long target = targetViewsForProgram(pv, cset, null);
    	  int eff = GridUtils.calculateEfficiency(total, target);
          addProgramToIntBucket(programsSortedByEff, pv, eff);
      }

      List<ProgramView> rtnList = selectProgramListSegmentByValue(programsSortedByEff, fromCountPct, toCountPct);
      Collections.sort(rtnList, myPgComparatorByEfficiency);
      
      return rtnList;
   }
   
   /**
    * Select a portion of a list of <code>ProgramViews</code> which have been ordered by integer
    * keys.  The portion is indicated by percentage of the list.
    * @param fromCountPct Percent of the list by total impressions to start from.
    * @param toCountPct Percent of the list by total impressions to end before.
    * @return List of <code>ProgramViews</code> in this segment of the original list.
    */
   private List<ProgramView> selectProgramListSegmentByPct(Map<Integer, List<ProgramView>> map, int fromCountPct, int toCountPct)
   {
      List<ProgramView> rtnList = new LinkedList<ProgramView>();
      AdConstraintSet cset = constraints();
      boolean checkInProg = cset.isSet(AdConstraintSet.Constraint.IN_PROGRAM);
      Set<Integer> keys = map.keySet();
      int size = keys.size();
      int first = (fromCountPct * size) / 100;
      int last = (toCountPct * size) / 100;
      Iterator<Integer> it = keys.iterator();

      for (int cur = 0; it.hasNext(); cur++)
      {
         int key = it.next();
         // get to the first key
         if (cur < first)
            continue;
         // break on the last key
         if (cur >= last)
            break;
         List<ProgramView> pvList = map.get(key);
         // add all the programs having this count to our list
         for (ProgramView pv : pvList)
         {
            if (canAddProgramToList(pv, cset, checkInProg))
            {
               // Order in descending order (opposite of tree ordering)
               rtnList.add(0, pv);
            }
         }  
      }
      return rtnList;
   }
   
   /**
    * Select a portion of a list of <code>ProgramViews</code> which have been ordered by integer
    * keys.  The portion is indicated by the 'to' and 'from' values of the list.
    * @param fromCount Value of total impressions to start from (inclusive).
    * @param toCount Value of total impressions to end before (exclusive).
    * @return List of <code>ProgramViews</code> in this segment of the original list.
    */
   private List<ProgramView> selectProgramListSegmentByValue(Map<Integer, List<ProgramView>> map, int fromCount, int toCount)
   {
      List<ProgramView> rtnList = new LinkedList<ProgramView>();
      AdConstraintSet cset = constraints();
      boolean checkInProg = cset.isSet(AdConstraintSet.Constraint.IN_PROGRAM);
      Iterator<Integer> it = map.keySet().iterator();

      // Loop through all the integer values of the map
      while (it.hasNext())
      {
         int key = it.next();
         // If this integer value falls within the requested range, 
         // add all programs with that value.
         if (key >= fromCount && key < toCount)
         {
            List<ProgramView> pvList = map.get(key);
            // add all the programs having this value to our list
            for (ProgramView pv : pvList)
            {
               if (canAddProgramToList(pv, cset, checkInProg))
               {
                  rtnList.add(pv);
               }
            }
         }  
      }
      return rtnList;
   }
   
   /**
    * Generic program list support method to add a <code>ProgramView</code> to
    * a list keyed by a string.  Used to support formatter and list view.
    * @param bucket Map that hashes a list of programs to a string.
    * @param pv <code>ProgramView</code> to add.
    * @param key String key associated with list.
    */
   private void addProgramToStrBucket(Map<String, List<ProgramView>> bucket, ProgramView pv, String key)
   {
      synchronized (bucket)
      {
         List<ProgramView> pList = bucket.get(key);
         if (pList == null)
         {
            pList = new LinkedList<ProgramView>();
            bucket.put(key, pList);
         }
         pList.add(pv);
      }
   }
   
   /**
    * Generic program list support method to add a <code>ProgramView</code> to
    * a list keyed by a integer.  Used to support formatter and list view.
    * @param bucket Map that hashes a list of programs to a string.
    * @param pv ProgramView to add.
    * @param key Integer key associated with list.
    */
   private void addProgramToIntBucket(Map<Integer, List<ProgramView>> bucket, ProgramView pv, Integer key)
   {
      synchronized (bucket)
      {
         List<ProgramView> pList = bucket.get(key);
         if (pList == null)
         {
            pList = new LinkedList<ProgramView>();
            bucket.put(key, pList);
         }
         pList.add(pv);
      }
   }
   
   /**
    * Generic program list support method to get a list of <code>ProgramView</code> objects
    * keyed to some string.  Used to support formatter's program list view.
    * @param bucket Map that hashes a list of programs to a string.
    * @param key String key associated with list.
    * @param comparator Comparator to use to sort output list.
    * @return ProgramView list.
    */
   private List<ProgramView> getProgramsFromStrBucket(Map<String, List<ProgramView>> bucket, String key, Comparator<ProgramView> comparator)
   {
      synchronized (bucket)
      {
         List<ProgramView> curList = bucket.get(key);
         return (processProgramList(curList, comparator));
      }
   }
   
   /**
    * Generic program list support method to get a list of <code>ProgramView</code> objects
    * keyed to some integer.  Used to support formatter's program list view.
    * @param bucket Map that hashes a list of programs to an integer.
    * @param key Integer key associated with list.
    * @param comparator Comparator to use to sort output list.
    * @return ProgramView list.
    */
   private List<ProgramView> getProgramsFromIntBucket(Map<Integer, List<ProgramView>> bucket, Integer key, Comparator<ProgramView> comparator)
   {
      synchronized (bucket)
      {
         List<ProgramView> curList = bucket.get(key);
         return (processProgramList(curList, comparator));
      }
   }
   
   /**
    * Generic program list support method to return a list of <code>ProgramView</code> objects
    * that are 'in program' according to the current view constraints from a larger list.
    * Apply current <code>AdConstraintSet</code> and only show programs that are IN_PROGRAM
    * if that constraint has been set on the view, and programs with non-zero estimated impressions.
    * @param fromList Program list to select from.
    * @param comparator Comparator to use to sort output list.
    * @return new sorted program list.
    */
   private List<ProgramView> processProgramList(List<ProgramView> fromList, Comparator<ProgramView> comparator)
   {
      AdConstraintSet cset = constraints();
      List<ProgramView> rtnList = new ArrayList<ProgramView>();
      boolean checkInProg = cset.isSet(AdConstraintSet.Constraint.IN_PROGRAM);
      if (fromList != null)
      {
         for (ProgramView pv : fromList)
         {
            if (canAddProgramToList(pv, cset, checkInProg))
            {
               rtnList.add(pv); 
            }
          }
      }
      // Return the list if it doesn't need to be sorted
      if (rtnList.size() == 0 || rtnList.size() == 1)
      {
         return rtnList;
      }
      // Sort programs
      Collections.sort(rtnList, comparator);
      return rtnList;
   }
   
   /**
    * Generic program list support method to return the list of keys for a 
    * specific bucket.
    * @param bucket Map that hashes a list of programs to a string.
    * @return the list of keys for this map.
    */
   private List<String> getBucketStrKeys(Map<String, List<ProgramView>> bucket)
   {
      List<String> keyList = new ArrayList<String>();
      synchronized(bucket)
      {
         keyList.addAll(bucket.keySet());
      }
      return keyList;
   }
   
   /**
    * Generic program list support method to return the list of integer keys for a 
    * specific bucket.
    * @param bucket Map that hashes a list of programs to an integer.
    * @return the list of integer keys for this map.
    */
   private List<Integer> getBucketIntKeys(Map<Integer, List<ProgramView>> bucket)
   {
      List<Integer> keyList = new ArrayList<Integer>();
      synchronized(bucket)
      {
         keyList.addAll(bucket.keySet());
      }
      return keyList;
   }
   
   /**
    * Return true if this program is eligible to be shown in a program list.
    * @param pv Program to check.
    * @param cset Constraint set to check against.
    * @param checkInProg Do we check if it's in program?
    * @return true if is worthy, false if not.
    */
   private boolean canAddProgramToList(ProgramView pv, AdConstraintSet cset, boolean checkInProg)
   {
      // If we are checking if in program, and this program isn't, discard.
      if (checkInProg && !isInProgram(pv.viewId()))
      {
          return false;
      }
      // If there are no estimated impressions, discard.
      if (totalADViewsForProgram(pv, cset, null) == 0)
      {
         return false;
      }
      return true;
   }
   
   /********************************************************************
    *      S T A T E   C H A N G E   N O T I F I C A T I O N
    ********************************************************************/
   /**
    * Attach a property change listener to this grid.  The grid will
    * sent a <code>PropertyChangeEvent</code> to all registered
    * listeners whenever its data changes.
    * @param listener Property Change Listener to add.
    */
   public void addPropertyChangeListener(PropertyChangeListener listener)
   {
      synchronized(myListeners)
      {
         myListeners.addPropertyChangeListener(listener);
      }
   }
   /**
    * Remove a property change listener from this grid.  
    * @param listener Property Change Listener to add.
    */
   public void removePropertyChangeListener(PropertyChangeListener listener)
   {
      synchronized(myListeners)
      {
         myListeners.removePropertyChangeListener(listener);
      }
   }
   
   /**
    * Tell listeners that the model has changed.
    * Set the dirty bit.
    */
   void fireGridChanged()
   {
      synchronized(myListeners)
      {
         myListeners.firePropertyChange(GRID_DIRTY_PROPERTY, false, true);
      }
   }
   
   /********************************************************************
    *             A U C T I O N   S U P P O R T 
    ********************************************************************/
   /**
    * @return The current Auctioneer object for this Grid.
    * No guarentees are made that an auction has been run yet.
    */
   public Auctioneer currentAuctioneer()
   {
	   return myAuctioneer;
   }
   
   /**
    * Get the date range across which the client wishes to auction.
    * Typically, for a simulated auction, it is over all time.
    * @return DateRange.
    * @see AuctionClient
    */
   public DateRange auctionDateRange()
   {
      return myAuctionDateRange;
   }
   
   /**
    * Set the date range across which the client wishes to auction.
    * Typically, for a simulated auction, it is over all time.
    * @param range DateRange.
    * @see AuctionClient
    */
   public void setAuctionDateRange(DateRange range)
   {
      myAuctionDateRange = range;
   }
   
   /**
    * Set whether to use auction wins when calculating baseline
    * efficiency for this grid's buy before running an auction.
    * @param useWins If true, buy's pre-auction baseline efficiency is based
    *    only avails marked as winners in the soup.  If false,
    *    all avails in the soup are used to determine baseline efficiency.
    */
   public void setUseAuctionWinsForBaselineEff(boolean useWins)
   {
       myUseLastAuctionWinsForBaselineEff = useWins;
   }
   
   /**
    * Get whether to use auction wins when calculating baseline
    * efficiency for this grid's buy before running an auction.
    * @return If true, buy's pre-auction baseline efficiency is based
    *    only avails marked as winners in the soup.  If false,
    *    all avails in the soup are used to determine baseline efficiency.
    */
   public boolean useAuctionWinsForBaselineEff()
   {
       return myUseLastAuctionWinsForBaselineEff;
   }
   
   /**
    * Get the baseline efficiency of this grid's buy
    * that was used for the previously-run auction.
    * @return Baseline efficiency used in last auction.
    */
   public float getLastAuctionBaselineEff()
   {
       return myLastAuctionBaselineEff;
   }
   
   /**
    * Current cost of this ad buy from last auction.
    * @return Cost of ad buy.
    *     The value is 0 until a simulated auction process has taken place
    *     or if we are not looking at auction wins.
    */
   public Money totalCost()
   {
       if (!isViewingAuctionWins())
       {
           return Money.ZERO;
       }
       return myAuctioneer.lastAuctionCost();
   }
   
   /**
    * For the last simulated auction, get a breakdown of spending & impressions
    * by <code>DayOfWeek</code> for this grid's AdBuy.
    * @return A map of campaign buy spending values keyed by <code>DayOfWeek</code>.
    */
   public Map<DayOfWeek,AuctionTally> costByDayOfWeek()
   {
	   int campaignID = 0;
	   int buyID = 0;
	   CampaignBuy adBuy = adBuy();
	   if (adBuy != null)
	   {
		   campaignID = myAdBuy.getCampaignId();
		   buyID = myAdBuy.getId();
	   }
      return myAuctioneer.getDayOfWeekTally(campaignID, buyID);
   }

   /**
    * Get derived cost per thousand views for this ad buy.
    * @return CPM for this ad buy in dollars per thousand.
    *     The value is 0 until a simulated auction process has taken place
    *     or if we are not looking at auction wins.
    */
   public float derivedCpm()
   {
       if (!isViewingAuctionWins())
       {
           return 0;
       }
       return myAuctioneer.lastAuctionCpm();
   }
   
   /**
    * Get this grid's buy's winners from the last proposal auction
    * (or an empty set if no proposal auction has been run yet).
    * @return Set of winning bids for this grid's buy.
    */
   public Set<AuctionObjectShadow> lastProposalAuctionWinners()
   {
       Auctioneer pa = currentProposalAuctioneer();
       if (pa == null)
       {
           return new HashSet<AuctionObjectShadow>();
       }
       return pa.sortBidsByIndex(true, true);
   }
   
   /**
    * @return Auction spending info for our client buy. If we are
    *     not viewing auction results or we have not run an auction, 
    *     return a zero spending representation.  Never returns null.
    */
   public CampaignBuyAuctionTally lastAuctionCampaignBuySpending()
   {
      CampaignBuyAuctionTally sp = myAuctioneer.lastAuctionCampaignBuySpending();
       if (!isViewingAuctionWins() || sp == null)
       {
           return Auctioneer.NO_CAMPAIGN_BUY_SPENDING;
       }
       return sp;
   }
   
   /**
    * Get the HTML representation of the last auction winners.
    * @return HTML page representing last auction.
    */
   public String lastAuctionWinsHtml()
   {
       String html = AuctionHtmlUtils.formatHtmlToString(myAuctioneer.lastAuctionBids());
       return html;
   }
   
   /**
    * Get the HTML representation of the last auction winners as placements
    * for the Opt Tool debugging, with one HTML table per buy.
    * @return HTML page representing last auction's placements.
    */
   public String lastAuctionPlacementsHtml()
   {
       String html = AuctionHtmlUtils.formatPlacementsToHTMLString(mySpotCache, 
               myAuctioneer.lastAuctionBids());
       return html;
   }
   
   /**
    * Get the delimited text representing the bid state from the
    * last auction, formatted the same way as the LAST_AUCTION.txt file.
    * If 'gridBidsOnly' is true, only dump the bid results for this grid;
    * otherwise, dump the results from the entire auction.
    * @return String representation of bids.
    */
   public String lastAuctionBids(boolean gridBidsOnly, boolean winnersOnly)
   {
       StringWriter sw = new StringWriter();
       PrintWriter pw = new PrintWriter(sw);
       if (gridBidsOnly)
       {
           myAuctioneer.dumpClientBids(pw, winnersOnly);
       }
       else
       {
           myAuctioneer.dumpDetails(pw);
       }

       return sw.toString();
   }
   
   /**
    * Print all bids from the previous auction, formatted
    * as an HTML table suitable for displaying in a web browser.
    * If 'gridBidsOnly' is true, only dump the bid results for this grid;
    * otherwise, dump the results from the entire auction.
    * @param pw PrintWriter to which to write the HTML content.
    * @param gridBidsOnly If true, only client bids.
    */
   public void lastAuctionBidsHtml(PrintWriter pw, boolean gridBidsOnly, boolean winnersOnly)
   {
       if (gridBidsOnly)
       {
           myAuctioneer.dumpClientHtmlBids(pw, winnersOnly);
       }
       else
       {
           myAuctioneer.dumpAllHtmlBids(pw, winnersOnly);
       }
   }
   /**
    * Get the delimited text representing the bid state from the
    * last auction, formatted the same way as the LAST_AUCTION.txt file, with
    * demographic data for each bid appended to the end of each row.
    * If 'gridBidsOnly' is true, only dump the bid results for this grid;
    * otherwise, dump the results from the entire auction.
    * @param gridBidsOnly If true, only format client (grid) bids.
    * @return String representation of bids.
    */
   public String lastAuctionGridBidsWithDemographics(boolean gridBidsOnly)
   {
       StringWriter sw = new StringWriter();
       PrintWriter pw = new PrintWriter(sw);
       DemographicCache dc = getSpotScheduleCache().getDemographicCache();
       myAuctioneer.dumpBidsWithDemographics(dc, pw, gridBidsOnly);
       return sw.toString();
   }
   
   /**
    * Get the debug output results for the SegmentSet actions
    * that occurred during the previous auction. The
    * setSegmentDebug must have been set to be true before
    * the auction started.
    * @return Text showing the SegmentSet actions for last auction.
    * @see AdAuctionApp.Auction.Auctioneer#setSegmentDebug
    */
   public String lastAuctionSegmentActions()
   {
       StringWriter sw = new StringWriter();
       PrintWriter pw = new PrintWriter(sw);
       myAuctioneer.dumpSegmentedAvailActions(pw);
       return sw.toString();
   }
   
   /**
    * Run an auction using passed-in auction contraints.
    * @param inProg Auction only IN Program spots.
    * @param inAud Auction only spot in target audience.
    * @param usePlc Auction using advertiser placement rules.
    * @param useBudget Auction using advertiser budget limits.
    */
   public void runAuction(boolean inProg, boolean inAud, boolean usePlc, boolean useBudget)
   {
      AuctionViewToggles at = new AuctionViewToggles(inProg, inAud, usePlc, useBudget);
      setAuctionConstraints(at);
      runAuction();
   }
   
   /**
    * Run an auction based on the current state of the Grid.
    */
   public void runAuction()
   {   
	   // Ensure one at a time.
	   if (!myIsAuctioningOK.getAndSet(false))
	   {
		   theLogger.debug("==> Attempting to auction same grid concurrently!");
		   return;
	   }

       synchronized(myAuctionLock)
       {
    	   theLogger.debug("==> Starting GREENFIELD Auction for Buy #" + adBuyId());
           myOperationNotifier.checkProgressBeforeAuction();
           
           // Run auction #1 to establish a baseline efficiency for the buy.
           setUseAuctionWinsForBaselineEff(false);
           myAuctioneer.runAuction(AuctionType.GREENFIELD);
           fireGridChanged();
           
           theLogger.debug("==> Ending GREENFIELD Auction.  ID: " + myAuctioneer.id());
           
           // If there is no target audience, BL will always be 100; if buy is
           // are active, BL eff is stored value.  We're done.
           if (!myAdBuy.hasSubscriberBehavior() || myAdBuy.isActiveState())
           {
               myIsAuctioningOK.getAndSet(true);
        	   return;
           }
           
           theLogger.debug("==> Starting BASELINE Auction for Buy #" + adBuyId());
           
           // Now run auction #2 that uses that resulting baseline efficiency.
           setUseAuctionWinsForBaselineEff(true);
           myAuctioneer.runAuction(AuctionType.BASELINE);
           fireGridChanged();
           
           myIsAuctioningOK.getAndSet(true);
           
           theLogger.debug("==> Ending BASELINE Auction.  ID: " + myAuctioneer.id());
       }
   }
   
   /********************************************************************
    *             P R O P O S A L    S U P P O R T 
    ********************************************************************/
   
   /**
    * Generate proposal information for this Grid.  We create
    * a separate parallel grid to calculate proposal numbers.
    * As a side effect, we record the baseline efficiency in our AdBuy.
    * @return Proposal grid.
    */
   public synchronized ProposalInfo generateProposalInfo()
   {
      MasterGrid pGrid = createProposalGrid();
      ProposalInfo pi = createGridProposalInfo(pGrid);
      myCurrentProposalInfo = pi;
      myCurrentProposalAuctioneer = pGrid.currentAuctioneer();
      pGrid.shutdown();
      // Set baseline efficiency on AdBuy.  Activating this
      // AdBuy will store this value as part of activation proc.
      CampaignBuy adBuy = adBuy();
      if (adBuy != null)
         adBuy.setBaselineEfficiency(pi.baselineEff);
      return pi;
   }
   
   /**
    * Given a MasterGrid, extract relevant information to create
    * and populate a ProposalInfo object.
    * @param grid to interrogate.
    * @return New ProposalInfo object.
    */
   public static ProposalInfo createGridProposalInfo(MasterGrid grid)
   {
       long analogDigital = grid.sumTotalViews(false);
       long digital = grid.sumTotalViews(true);
       long target = grid.sumTargetViews();
       Money cost = grid.totalCost();
       float cpm = grid.derivedCpm();
       float blEff = grid.getLastAuctionBaselineEff();
       ProposalInfo pi = new ProposalInfo(grid.startDate(),
             grid.endDate(),
             blEff,
             digital,
             analogDigital,
             target,
             cpm,
             cost);
       return pi;
   }
   
   /**
    * Get the current proposal info.  If no proposal has
    * been generated yet, return null.
    * @return Last generated Proposal Info, or null if 
    * no proposal has been generated.
    */
   public ProposalInfo currentProposalInfo()
   {
	   return myCurrentProposalInfo;
   }
   
   /**
    * Get the Auctioneer object that ran the proposal
    * auction.  Useful for collecting stats on the proposal auction.
    * @return Proposal Auctioneer, or null if no proposal auction has been run.
    */
   public Auctioneer currentProposalAuctioneer()
   {
	   return myCurrentProposalAuctioneer;
   }
   /**
    * Create a new proposal grid based on our current settings.
    * This creates a grid using our current spot cache and 
    * Ad Buy, and sets its DateRange to be that of our original grid.  
    * It also turns all auction constraints
    * on, causing an auction to be run, and waits for this to complete.
    * When this call returns, it is ready to provide proposal data, such as
    * baseline efficiency and estimated cost.
    * @return Configured Proposal grid.
    */
   private MasterGrid createProposalGrid()
   {
      MasterGrid propGrid = new MasterGrid(mySpotCache, myAdBuy, true, myIsStandaloneMode);
      propGrid.setConstraints(new AdConstraintSet(IN_PROGRAM,IN_AUDIENCE,AUCTION_WIN));
      propGrid.runFullSynchronousAuction(this.startDate(), this.endDate());
      return propGrid;
   }
   
   /**
    * Run an auction with all toggles turned ON over a specified date range
    * on this Grid.  Wait for the task to finish before returning.
    * The View toggles are set to be all ON on this grid when this returns.
    * @param start Start of auction period.
    * @param end End of synchronous auction.
    */
   public void runFullSynchronousAuction(Calendar start, Calendar end)
   {
	  myProgressTracker.clearLastError();
	  
      // Initially, we don't auction, so we can do so synchronously, below.
      setDateRange(start, end, AuctionViewToggles.NO_CONSTRAINTS);
      
      // If in testing mode - just run the auction.
      if (myIsStandaloneMode)
      {
    	  runAuction(true, true, true, true);
    	  return;
      }
      
      // Now have a DTC run an auction for us.  
      // The toggles will be passed in by the task.
      CentralAdvertisingTargetManager tmgr = 
         CentralAdAuctionAppServer.centralInstance().centralAdvertisingTargetMgr();
      
      try
      {
         final CampBuyOperationDTC currentDTC = 
            tmgr.performMultipleCampaignBuyOperations(adBuy(),
               this,
               true,  // programs
               true,  // reach
               true,  // run auction
               true,  // inProgram,
               true,  // inAudience,
               true,  // usePlacement,
               true); // useBudget;
         // 
         // On completion of all operations, wake up any waiters.
         currentDTC.setCallback(new DistributedRequestCompleteCallbackIFace() {
            public void distributedRequestComplete (DistributedTaskCoordinator coordinator)
            {
               synchronized(currentDTC)
               {
                  currentDTC.notifyAll();
               }
            }
         });
         //
         // Now wait for all operations to finish.
         // If we are not active, we are already finished.
         // If we are still working, wait to be notified by callback above.
         synchronized(currentDTC)
         {
            // If our task is active, wait for it.
            if ( currentDTC.isActive())
            {
               try
               {
                  currentDTC.wait();
               }
               catch (InterruptedException ex)
               {
                  throw new AdAuctionAppException("Full auction task interrupted");
               }
            }
         }
      }
      catch (AdAuctionAppException aee)
      {
         errorMsg("runFullSynchronousAuction()", 
               "estimate reach for AdBuy #" + adBuyId() +
               " failed.", aee);
      }
      // Now set the view constraints to full
      setConstraints(new AdConstraintSet(IN_PROGRAM,IN_AUDIENCE,AUCTION_WIN));
   }

   /********************************************************************
    *             P R O G R E S S    S U P P O R T 
    ********************************************************************/
   
   /**
    * Initialize a possibly lengthy process associated with this grid.
    * @param desc String that describes the task whose progress is being tracked.
    * @return ID for use with ProgressTracker.
    */
   int startProgress(String desc)
   {
      int taskId = myProgressTracker.startTask(desc);
      fireGridChanged();
      return taskId;
   }
   
   /**
    * Get percent complete of any ongoing calculation that affects this grid.
    * Set by notifications by tasks that were started by UI controller.
    * Calculation examples include multi-org estimated reach or auctions.
    * @return Percentage complete of any ongoing calculation.
    *         Range is 0 (not yet started) to 100 (complete).
    */
   public int progressPercent()
   {
      return myProgressTracker.percentComplete();
   }
   
   /**
    * Get the accompanying message(s) for an ongoing progress task.
    * @return Message string accompanying a registered progress or
    *         an empty string if none.
    */
   public String progressMessage()
   {
      return myProgressTracker.curProgressMessage();
   }
   
   /**
    * Get any error message from the progress tracker.
    * @return Error message or null if none.
    */
   public String progressErrorMessage()
   {
      return myProgressTracker.errorMessage();
   }
   
   /**
    * Get any warning message from the progress tracker.
    * @return Warning message or null if none.
    */
   public String progressWarningMessage()
   {
      return myProgressTracker.warningMessage();
   }
   
   /**
    * Clear the state of the progress notifier, clear the
    * progress message, and set the progress percent to PROGRESS_DONE.
    */
   void clearProgress(int tid)
   {
      myProgressTracker.complete(tid);
      fireGridChanged();
   }
  
   /**
    * Set a progress notifier.  Called by a subsystem which we have registered
    * with to notify us that a task has started that we can report progress
    * about to our UI (via calls to progressMessage() and progressPercent().
    * @param msg String description of ongoing task to be shown user.
    * @param notifier CampBuyOperationDTC that holds task state.
    * @param finisher Code to execute upon completion, or null if none.
    */
   void setProgressNotifier(String desc, 
                            CampBuyOperationDTC notifier,
                            ProgressTracker.Completion finisher)
   {
      myProgressTracker.startTask(desc, notifier, finisher);
      fireGridChanged();
   }
   
   /**
    * This method gets necessary data for the grid from cache
    */
   private boolean createGridSoupFromCache()
   {
      theLogger.info("SoupCreator: createGridSoupFromCache().");

      // Record that we're starting a soup create process.
      int progId = startProgress(SOUP_CREATE_MSG);
      
      // Create our soup of available exchanges
      mySoup = new AdBuySoup(this);
      
      // get programs for the grid
      myProgramViews = getValidProgramViewsForGrid();
      // add programs to the bucket
      addProgramsToBuckets(myProgramViews);
      
      // Initialize all programs at first to 'In Program'.
      includeAllPrograms();
      
      clearProgress(progId);
      
      return true;
   }
   
   /**
    * Get a list of all program views that are valid for this AdBuy
    * (have breaks for targeted orgs and in date range).
    * @param grid MasterGrid of AdBuy.
    * @return All ProgramView objects in our soup.
    */
   private List<ProgramView> getValidProgramViewsForGrid()
   {
      List<ProgramView> programs = new LinkedList<ProgramView>();
      programs.addAll(mySoup.programViewSet());
      return programs;
   }

   /**
    * Get the total number of available spots in this grid that are 
    * considered within the ad buy's defined program content.  
    * Spots must also be valid and within the ad buy's date range.
    * @return Total number of spots considered 'in program'.
    */
   public int totalInProgramSpots()
   {
      int total = 0;
      
      AdConstraintSet cset = new AdConstraintSet(IN_PROGRAM);
      for (CableChannel chan : channels())
      {
         List<ProgramView> programs  = programViewList(chan, cset);
         for (ProgramView p : programs)
         {
            Set<Integer> target_orgIDs = getTargetOrganizationIDs();
            for (int orgID : target_orgIDs)
            {
               List<BreakView> breakList = p.getBreaks(orgID);
               for (BreakView bv: breakList)
               {
                  total += bv.getSpots().length;
               }
            }
         }
      }
      return total;
   }
   
   /**
    * Check whether this ProgramView is valid for 
    * this MasterGrid.
    * @param pv ProgramView to test.
    * @return true if this ProgramView has breaks in our
    * AdBuy's target organizations, and is within
    * its date range.
    */
   private boolean isValidForGrid(ProgramView pv)
   {
      // check if the program view is in our date range.
      if (isInDateRange(pv) == false)
      {
         return false;
      }
      
      // Now check if it has at least one break in any of our orgs.
      Set<Integer> orgIdSet = pv.getOrgIds();
      for (int orgID : orgIdSet)
      {
         if (isDestinationOrganization(orgID))
         {
            return true;
         }
      }
      // None of its Org IDs match ours
      return false;
   }
   
   /********************************************************************
    *             T E S T    M E T H O D S
    ********************************************************************/
   
   /**
    * @return true if in synchronous mode (no background tasks are used for DB access)
    *         where no AdAuctionAppServer facilities are used in this mode.
    */
   public boolean isStandaloneMode()
   {
      return myIsStandaloneMode;
   }
   
   /**
    * Dump statistics for this grid
    * @return String statistics about this grid.
    * @see tv.navic.Task.StatsDumpIFace
    */
   public String dumpStatistics()
   {
	  final String NL = "\n   ";
      StringBuffer sb = new StringBuffer("---------------------------\n");
      sb.append("GRID STATS: BUY #" + adBuyId());
      sb.append(" (GridID #" + getGridID() + ")");
      if (myIsProposal)
      {
    	  sb.append(" [Proposal]");
      }
      sb.append(NL + "Created: " + createdDate());
      sb.append(", LastAccess: " + lastAccessDate());
      sb.append(NL + "Active State: " + adBuy().activeStateString());
      sb.append(NL + "Soup: Spots: " + String.format("%,d", mySoup.spotCount()));
      sb.append(", Breaks: " + mySoup.breakCount());
      sb.append(", InProgram views: " + myInProgramViewIDs.size());
      sb.append(" (out of " + myProgramViews.size() + " total Pviews)");
      sb.append(NL + "DateRange: " + GridUtils.formatShortDate(startDate()));
      sb.append(" to " + GridUtils.formatShortDate(endDate()));
      sb.append(NL + "View Contraints: " + myConstraintSet.toString());
      long digViews = sumTotalViews(true);
      long totViews = sumTotalViews(false);
      long targViews = sumTargetViews();
      float eff = GridUtils.calculateEfficiency(digViews, targViews);
      sb.append(NL + "TotalViews: " + String.format("%,d", totViews));
      sb.append(" (" + String.format("%,d", digViews) + " digital)");
      sb.append(" TargetViews: " + String.format("%,d", targViews));
      sb.append(" (" + String.format("%3.1f", eff) + "% efficiency)");
      sb.append(NL + "Auction Contraints: " + myAuctionToggles.toString());
      sb.append("\n" + myAuctioneer.dumpStatistics());
      Auctioneer propAuctioneer = currentProposalAuctioneer();
      if (propAuctioneer != null)
      {
         sb.append(NL + "<== Last Proposal Auction ==>\n" + 
                 propAuctioneer.dumpStatistics());
      }
      return sb.toString();
   }
   
   /**
    * Dump auction results to output stream.  If 'all',
    * print all bid results.
    * @param out Output to print results to.
    * @param all If true, print status of all bids.
    */
   public void dumpAuctionResults(PrintWriter out, boolean all)
   {
      out.print(myAuctioneer.dumpStatistics());
      if (all)
      {
         myAuctioneer.dumpDetails(out);
      }
   }
   
   /**
    * Dump the last set of multipliers we received for estimate reach.
    * @return String version of multipliers.
    */
   public String dumpLastMultipliers()
   {
      StringBuilder sb = new StringBuilder();
      for (OrgZoneChannelHour key : myLastMultipliers.keySet())
      {
         int multiplier = myLastMultipliers.get(key);
         String s = String.format("O:%04d Z:%04d Ch:%04d Hr:%04d M: %03d\n",
               key.orgID, key.zoneID, key.channelID, key.hourOfWeek, multiplier);
         sb.append(s);
      }
      return sb.toString();
   }
   
   /**
    * This should be called once per day to get and reset the
    * stats for that day.
    * @return a string representation of our daily statistics.
    */
   public String dailyStatistics()
   {
      return dumpStatistics();
   }
   
   /**
    * Convenience routine for printing debug messages.
    * @param method Calling method name.
    * @param message to print.
    */
   private static void debugMsg(String method, String message)
   {
      theLogger.debug(method + ": " + message);
   }
   
   /**
    * Convenience routine for printing tech messages.
    * @param method Calling method name.
    * @param message to print.
    */
   private static void techMsg(String method, String message)
   {
      theLogger.info(method + ": " + message);
   }
   
   /**
    * Convenience routine for printing error messages.
    * @param method Calling method name.
    * @param message to print.
    */
   private static void errorMsg(String method, String message, Throwable e)
   {
      if (e == null)
         theLogger.error(method + ": " + message);
      else
         theLogger.error(method + ": " + message, e);
         
   }

   /********************************************************************
    *             H E L P E R     C L A S S E S
    ********************************************************************/
   
   /**
    * ProposalInfo
    * An immutable data container class for Proposal info.
    */
   public static class ProposalInfo
   {
      /**
       * Constructor
       * @param sDate Start date
       * @param eDate End date
       * @param blEff Baseline efficiency used to produce proposal results.
       * @param digital Estimated Total digital views.
       * @param analogDigital  Estimated Total digital + analog views.
       * @param target  Estimated Target (digital-only) views.
       * @param cpm Derived cost per thousand views.
       * @param cost Total cost of this buy.
       */
      public ProposalInfo(Calendar sDate, Calendar eDate, float blEff, long digital, long analogDigital, 
    		  long target, float cpm, Money cost)
      {
         this.startDate = sDate;
         this.endDate = eDate;
         this.totalDigitalViews = digital;
         this.totalAnalogDigitalViews = analogDigital;
         this.targetViews = target;
         this.derivedCpm = cpm;
         this.totalCost = cost;
         this.baselineEff = blEff;
      }
      /** Start date */
      public final Calendar startDate;
      /** End date */
      public final Calendar endDate;
      /** Estimated Total digital views */
      public final long totalDigitalViews;
      /** Estimated Total digital + analog views */
      public final long totalAnalogDigitalViews;
      /**  Estimated Target (digital-only) views. */
      public final long targetViews;
      /** Derived cost per thousand views. */
      public final float derivedCpm;
      /** Total cost of this buy. */
      public final Money totalCost;
      /** Baseline efficiency on buy. */
      public final float baselineEff;
      
   } // END ProposalInfo class
   
   /**
    * AdBuyNotifier
    * Helper class that allows this grid to register to be told about various
    * long-running tasks that it is interested in, so it can report back to 
    * the UI about results of these calculations that may take a relatively long time.
    * The CampaignBuyOperationNotificationIFace and AuctionOperationNotificationIFace 
    * interfaces will notify us of all Ad Buy calculations, so we have
    * to filter for those calculations that only are made against our Ad Buy.
    * We are told when a calculation starts and are provided a DTC, so we may register 
    * a callback to be called by the DTC when it is finished its calculations.
    */
   private class AdBuyNotifier implements CampaignBuyOperationNotificationIFace,
                                 AuctionOperationNotificationIFace
   {
      
      /** Callback made when a estimate reach request is made against a campaign buy.
       *  @param dtc The CampBuyOperation Distributed Task Coordinator object 
       *             associated with the operation.
       */
      public void estimateReachStarted(CampBuyEstimateReachDTC dtc)
      {
         if (isMatch(dtc.campaignBuyID()))
         {
            setIsEstReachRunning(true);
            setProgressNotifier(ESTIMATE_REACH_MSG, dtc, myEstimateReachCompletion);
         }
      }
      
      /** Callback made when a calculate programs request is made against a campaign buy.
       *  @param dtc The CampBuyOperation Distributed Task Coordinator object 
       *             associated with the operation.
       */
      public void calculateProgramsStarted(CampBuyCalculateProgramsDTC dtc)
      {
         if (isMatch(dtc.campaignBuyID()))
         {
            setIsCalcProgs(true);
            setProgressNotifier(PROGRAM_CALCULATE_MSG, dtc, myCalcProgsCompletion);
         }
      }
      /** Callback made when an auction request is made against a campaign buy.
       *  @param dtc The AuctionOperation Distributed Task Coordinator object 
       *       associated with the operation.
       */
      public void simulatedAuctionStarted(AuctionOperationDTC dtc)
      {
         if (isMatch(dtc.campaignBuyID()))
         {
            setAuctionRunning(true);
            setProgressNotifier(AUCTIONING_MSG, dtc, myAuctionCompletion);
         }
      }
      
      /** 
       * Callback made when a mutiple operation request is made against
       * a campaign buy.  Unpack the multi-operation and start a notifier for each.
       * @param dtc The CampBuyMultiOperationDTC object associated with
       * the operation.
       */
      public void multiOperationStarted (CampBuyMultiOperationDTC dtc)
      {
         myProgressTracker.setMultiTask(true);
         if (dtc.hasEstimateReach())
         {
            estimateReachStarted(dtc.estimateReachDTC());
         }
         if (dtc.hasAuction())
         {
            simulatedAuctionStarted(dtc.auctionDTC());
         }
         if (dtc.hasCalculatePrograms())
         {
            calculateProgramsStarted(dtc.calculateProgramsDTC());
         }
      }
      
      /**
       *  @return true if this program calculation is for our ad buy.
       */
      private boolean isMatch(int aId)
      {
         return (aId == adBuyId());
      }
      
      /**
       * Set whether we've started an estimate reach.
       * @param isRunning if true, we are starting an estimate reach, so add
       * to our count.  If false, an estimate reach task has completed,
       * so subtract from our count.
       */
      private synchronized void setIsEstReachRunning(boolean isRunning)
      {
          if (isRunning)
              myRunningEstReach++;
          else
              myRunningEstReach--;
      }
      
      /**
       * @return true if one or more estimate reach tasks are in progress.
       */
      public synchronized boolean isEstReachRunning()
      {
          return (myRunningEstReach > 0);
      }
      
      /**
       * Set whether we've started an auction.
       * @param isRunning If true, we are running an auction
       */
      private synchronized void setAuctionRunning(boolean isRunning)
      {
          myRunningAuction = isRunning;
      }
      
      /**
       * @return true if we've started an auction.
       */
      public synchronized boolean isAuctionRunning()
      {
          return myRunningAuction;
      }
      /**
       * @return true if we're calculating program content.
       */
      public synchronized boolean isCalcProgs()
      {
          return myIsCalcProgs;
      }
      
      /**
       * Are all current tasks finished yet?
       * @return true if all done, false if not.
       */
      public boolean allTasksFinished()
      {
          if (!isCalcProgs() && !isEstReachRunning() && !isAuctionRunning())
          {
              return true;
          }
          return false;
      }
      
      /**
       * Set whether we've started calculating program content.
       * @param isRunning If true, we are calculating program content.
       */
      private synchronized void setIsCalcProgs(boolean isRunning)
      {
          myRunningAuction = isRunning;
      }
      
      /**
       * Call this before running a simulated auction.
       * If there is one or more estimate reach operations in 
       * progress, set a flag indicating that we'll need to run
       * another auction once all other operations are done,
       * since the auction running now may be based on
       * incomplete target estimation data.
       */
      public void checkProgressBeforeAuction()
      {
          if (isEstReachRunning())
          {
              theLogger.debug("WARNING: Grid " + getGridID() + 
                    " starting auction before est reach finished!");
              myRunAuctionOnCompletion = true;
          }
          if (isCalcProgs())
          {
              theLogger.debug("WARNING: Grid " + getGridID() + 
                    " starting auction before calculating programs is finished!");
              myRunAuctionOnCompletion = true;
          }
      }
      
      /**
       * If the flag has been set, run an auction and reset flag.
       * If not all tasks have finished, do nothing.
       */
      private void checkCompletionAuction(boolean wasSuccess)
      {
          if (!allTasksFinished())
          {
              return;
          }
          if (myRunAuctionOnCompletion == true)
          {
              myRunAuctionOnCompletion = false;
              if (wasSuccess)
              {
                  runAuction();
              }
          }
      }
      
      /**
       * Track whether program calculation is complete.
       */
      private ProgressTracker.Completion myCalcProgsCompletion = 
          new DefaultCompletion() {
              public void finish(boolean isSuccess)
              {
                  super.finish(isSuccess);
                  setIsCalcProgs(false);
                  checkCompletionAuction(isSuccess);
              }
      };
      
      /** Track whether we're getting an auction before an estimate reach */
      private ProgressTracker.Completion myEstimateReachCompletion = 
          new DefaultCompletion() {
              public void finish(boolean isSuccess)
              {
                  super.finish(isSuccess);
                  setIsEstReachRunning(false);
                  checkCompletionAuction(isSuccess);
              }
      };
      /** Track auction progress. */
      private ProgressTracker.Completion myAuctionCompletion = 
          new DefaultCompletion() {
              public void finish(boolean isSuccess)
              {
                  setAuctionRunning(false);
                  super.finish(isSuccess);
              }
      };
      // Count of estimate reach tasks running.
      private int myRunningEstReach = 0;
      private boolean myIsCalcProgs = false;
      private boolean myRunningAuction = false;
      private boolean myRunAuctionOnCompletion = false;
      
   } // END AdBuyNotifier class

   
   /**
    * Default completion task when any long-running calculation has
    * completed. Notify any grid listeners.
    */
   private class DefaultCompletion implements ProgressTracker.Completion
   {
      /** Run when task completes */
      public void finish(boolean isSuccess)
      {
         fireGridChanged();
      }
   } // END DefaultCompletion class
   
   /********************************************************************
    *             C L A S S     M E M B E R S
    ********************************************************************/
   
   /** Value of progressPercent() when all tasks that affect this grid are complete */
   public static final int PROGRESS_DONE = 100;
   /** Value of progressPercent when a task that affects this grid has started */
   public static final int PROGRESS_STARTED = 0;
   
   /** Message seen by UI when soup is being created. */
   public static final String SOUP_CREATE_MSG = "Gathering placement opportunities...";
   /** Message when soup is being destroyed (displayed typically only in debug output). */
   public static final String DESTROY_GRID_MSG = "Destroying grid";
   
   // Property used to alert Swing UI of updates.
   private static final String GRID_DIRTY_PROPERTY = "GRID_DIRTY";
   

   // A CriteriaChecker that always returns true.  Applied to
   // programs, it will set all programs to be 'In Program'.
   private static final CriteriaCheckerIFace INCLUDE_ALL_CRITERIA_CHECKER = 
      new CriteriaCheckerIFace() {
         public boolean evaluate (IndexedAttributeObject indexedObject)
         {
            return true;
         }
         public String dump(int level)
         {
            return "INCLUDE_ALL_CRITERIA_CHECKER - Always true!";
         }
      };
   
   // A comparator used to order programs returned for program view results.
   // Order by title, then day, then time.
   private final Comparator<ProgramView> myPgComparatorByTitle = new Comparator<ProgramView>() 
   {
      /**
       * Compare two ProgramViews by title, then day, then time..
       * @param pv1 first ProgramView to compare
       * @param pv2 second ProgramView to compare
       * @return integer indicating order
       * @see java.util.Comparator
       */
      public int compare(ProgramView pv1, ProgramView pv2) 
      {
         CachedProgram p;
         
         // get program title from cache
         p = mySpotCache.getProgramByID(pv1.programId());
         String s1 = p.title;
         
         p = mySpotCache.getProgramByID(pv2.programId());
         String s2 = p.title;
         if (s1.equalsIgnoreCase(s2))
         {
            int day1 = pv1.airDayOfYear();
            int day2 = pv2.airDayOfYear();
            if (day1 == day2)
            {
               int time1 = pv1.startMinutes();
               int time2 = pv2.startMinutes();
               return (new Integer(time1).compareTo(time2));
            } 
            else 
            {
               return (new Integer(day1).compareTo(day2));
            }
         }
         return s1.compareToIgnoreCase(s2);
      }
   };
      
   // A comparator used to order programs returned for program view results.
   // Order by time only.
   private static final Comparator<ProgramView> thePgComparatorByTime = new Comparator<ProgramView>()
   {
      /**
       * Compare two program views by start time.
       * @param pv1 first ProgramView to compare
       * @param pv2 second ProgramView to compare
       * @return integer indicating order
       * @see java.util.Comparator
       */
      public int compare(ProgramView pv1, ProgramView pv2) 
      {
         int time1 = pv1.startMinutes();
         int time2 = pv2.startMinutes();
         return (new Integer(time1).compareTo(time2));
      }
   };
   
   // A comparator used to order programs returned for program view results.
   // Order by efficiency, then impressions.
   private final Comparator<ProgramView> myPgComparatorByEfficiency = new Comparator<ProgramView>()
   {
      /**
       * Compare two program views by efficency, then by impressions.
       * @param pv1 first ProgramView to compare
       * @param pv2 second ProgramView to compare
       * @return integer indicating order
       * @see java.util.Comparator
       */
      public int compare(ProgramView pv1, ProgramView pv2) 
      {
         AdConstraintSet cset = constraints();
         long target1 = targetViewsForProgram(pv1, cset, null);
         long total1 = totalDigitalViewsForProgram(pv1, cset, null);
         long target2 = targetViewsForProgram(pv2, cset, null);
         long total2 = totalDigitalViewsForProgram(pv2, cset, null);
         int eff1 = GridUtils.calculateEfficiency(total1, target1);
         int eff2 = GridUtils.calculateEfficiency(total2, target2);
         if (eff1 == eff2)
         {
            return (new Long(total2).compareTo(total1));
         }
         return (new Integer(eff2).compareTo(eff1));
      }
      
      // 
   };

   /********************************************************************
    *             O B J E C T    M E M B E R S
    ********************************************************************/
   // Configuration
   private final Date myCreationDate;
   private final CampaignBuy myAdBuy;
   private final boolean myIsProposal;
   private int myGridID = 0;
   private Date myLastAccessDate;
   
   // SpotSchedule Cache
   private SpotScheduleCache mySpotCache = null;
   
   // Grid shadow for this Ad Buy
   private AdBuySoup mySoup = null;
   
   // target/destination organization & markets of the campaign buy
   private Set<Integer> myDestinationMsoIDs = null;
   private Set<Organization> myDestinationMSOs = null;
   private Set<Marketplace> myDestinationMarkets = null;
   private Map<Integer, List<NationalChannel>> myChannelsByOrgMap = null;
   
   // program views that satisfy the criteria
   private Set<Integer> myInProgramViewIDs = null;
   
   // Auction support
   private Auctioneer myAuctioneer = null;
   private ProposalInfo myCurrentProposalInfo = null;
   private Auctioneer myCurrentProposalAuctioneer = null;
   private boolean myUseLastAuctionWinsForBaselineEff = false;
   private float myLastAuctionBaselineEff = 0f;
   private final Object myAuctionLock = new Object();
   private AtomicBoolean myIsAuctioningOK = new AtomicBoolean(true);

   // grid date range
   private DateRange myDateRange = null;
   private DateRange myAuctionDateRange = null;
   private AuctionViewToggles myAuctionToggles = null;
 
   private AdConstraintSet myConstraintSet = null;
   private AdBuyNotifier myOperationNotifier = null;
   private ProgressTracker myProgressTracker = null;
   
   // program Data
   private Map<String, List<ProgramView>> myProgramsByCategory = null;
   private Map<String, List<ProgramView>> myProgramsByTitle = null;
   private Map<String, List<ProgramView>> myProgramsByMarket = null;
   private Map<String, List<ProgramView>> myProgramsByDaypart = null;
   private Map<Integer, List<ProgramView>> myProgramsByDayOfYear = null;
   private Map<Integer, List<ProgramView>> myProgramsByDayOfWeek = null;
   private Map<Integer, List<ProgramView>> myProgramsByTime = null;
   private List<ProgramView> myProgramViews = null;
   private TreeMap<Integer, List<ProgramView>> myProgramsSortedByImpressions = null;
   // Combined placement attributes for a spot video and associated market spot video. The outer key is
   // the creative id; the inner key is the mso org id; the value is the combined placement attributes
   private Map<Integer, Map<Integer, PlacementAttribute>>
           myPlacementAttrsByCreative = null;
   // Combined placement criteria for a spot video, associated market spot video, and organization. The outer key is
   // the creative id; the inner key is the mso org id; the value is the combined placement criteria
   private Map<Integer, Map<Integer, Collection<CriteriaCheckerIFace>>>
           myPlacementCriteriaByCreative = null;
   private int myBooleanAttributesCount;
   private int[] myDefaultIntegerAttributes;
   private String[] myDefaultStringAttributes;
   private Map<AttributeDataType, int[]> mySpotAttrMaps = 
      new HashMap<AttributeDataType, int[]>();
   private Map<Integer, MarketplaceSpotVideo[]> myMktSpotVideosByCreativeIDMap =
       new HashMap<Integer, MarketplaceSpotVideo[]>();

   // Support change support model
   private PropertyChangeSupport myListeners = null;
   // Demographics
   private long myDemoAudienceUniversalTotal = 0L;
   
   // TESTING mode: no threading / hammock / manager calls
   private final boolean myIsStandaloneMode;
   // The list of multipliers received from last estimate reach (for debugging)
   private Map<OrgZoneChannelHour,Integer> myLastMultipliers = null;
   
   /*********************** CLASS MEMBERS ****************************/
   private static CentralAdAuctionAppServer myCentralServer = 
      CentralAdAuctionAppServer.centralInstance();
   private static CentralAdvertisingTargetManager myAdTargetMgr = 
      CentralAdAuctionAppServer.centralInstance().centralAdvertisingTargetMgr();
   private static CentralAuctionManager myAuctionMgr = 
      CentralAdAuctionAppServer.centralInstance().centralAuctionMgr();
   // Logging
   private static Logger theLogger = Logger.getLogger(MasterGrid.class);
   
} // END MasterGrid class
