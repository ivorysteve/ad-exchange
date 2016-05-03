/**
 * Part of a source code package originally written for the adverting auction project.
 * Intended for use as a programming work sample file only.  Not for distribution.
 **/
package AdAuctionApp.AdBuyGrid;

import static AdAuctionApp.AdBuyGrid.AdConstraintSet.Constraint.AUCTION_WIN;
import static AdAuctionApp.AdBuyGrid.AdConstraintSet.Constraint.IN_AUDIENCE;
import static AdAuctionApp.AdBuyGrid.AdConstraintSet.Constraint.IN_PROGRAM;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ShortBuffer;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.border.BevelBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import AdAuctionApp.AdBuyGrid.AdBuySoup.CableChannel;
import AdAuctionApp.AdBuyGrid.AdBuySoup.DaypartCell;
import AdAuctionApp.AdBuyGrid.AdConstraintSet.ImpressionType;
import AdAuctionApp.AdBuyGrid.Auction.AuctionViewToggles;
import AdAuctionApp.AdvertisingTarget.CampaignBuy;
import AdAuctionApp.AdvertisingTarget.Compiled.CriteriaCheckerIFace;
import AdAuctionApp.Auction.AuctionPass;
import AdAuctionApp.Auction.AuctionSettings;
import AdAuctionApp.Auction.AuctionUtils;
import AdAuctionApp.Cache.IndexedAttributeObject;
import AdAuctionApp.Cache.Central.BreakView;
import AdAuctionApp.Cache.Central.GridUtils;
import AdAuctionApp.Cache.Central.ProgramView;
import AdAuctionApp.Core.DayOfWeek;
import AdAuctionApp.Core.Money;
import AdAuctionApp.Server.SystemParameter;
import AdAuctionApp.Util.Json.JSONArray;
import AdAuctionApp.Util.Json.JSONException;
import AdAuctionApp.Util.Json.JSONObject;

/**
 * Swing view widget for SpeedOfLight AdBuyGrid.
 */
public class AdBuyGridUI
extends JFrame 
implements PropertyChangeListener
{
   /**
    * Constructor.  Build UI.
    * @param grid Grid model
    */
   public AdBuyGridUI (MasterGrid grid)
   {
      super("AdBuy MasterGrid Viewer");
      myGrid = grid;
      myViewConstraints = grid.constraints();
      
      Logger.getRootLogger().setLevel(Level.DEBUG);
      
      myAdaptor = new GridTableAdapter(grid);
      final JTable table = new JTable(myAdaptor);
      table.setCellSelectionEnabled(true);
      table.setDefaultRenderer(Object.class, new GridCellRenderer());
      myGrid.addPropertyChangeListener(this);
      JScrollPane scroller = new JScrollPane(table);

      // Exit button
      JButton exitB = new JButton("Exit");
      exitB.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            shutdown();
         }
      });
      
      // Simulations - Audience
      JButton targAudButton = new JButton("Change Target Audience");
      targAudButton.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            Set<Integer> orgIds = myGrid.getDestinationOrgIDs();
            if (orgIds.size() == 0)
            {
               System.out.println("No OrgIDs in Grid!");
               return;
            }
            for (Integer orgID : orgIds)
            {
                setMultipliers(generateMultipliers(), orgID);
            }
            checkAuction();
         }
      });
      
      // Simulations - Program
      JButton inProgramButton = new JButton("Change Program Exclusion");
      inProgramButton.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            int pct = theRandomizer.nextInt(20);
            InProgramSetter checker = new InProgramSetter(pct);
            myGrid.calculatePrograms(checker);
            checkAuction();
         }
      });
      
      // Date
      JComboBox dateChooser = createDateChooser();
      JPanel dateChooserPanel = createLabeledBox("Change End Date:", dateChooser);
      
      // Budget
      myBudgetModel = new SpinnerNumberModel(1, 1, MAX_BUY_BUDGET, 10);
      JSpinner budgetSpinner  = new JSpinner(myBudgetModel);
      budgetSpinner.addChangeListener(new ChangeListener() {
          public void stateChanged(ChangeEvent ev) {
              // Recalculate all budgets.
              CampaignBuy buy = myGrid.adBuy();
              int val = myBudgetModel.getNumber().intValue();
              Money buyBudget = new Money(val, 0);
              buy.setSpendingLimit(buyBudget);
              buy.setCampaignSpendingLimit(buyBudget); // Set Campaign budget same as buy.
              checkAuction();
          }
       });
      JPanel budgetPanel = createLabeledBox("Change Buy Budget", budgetSpinner);
      
      // Target CPM
      float initCPM = myGrid.adBuy().getTargetCpm();
      myCPMModel = new SpinnerNumberModel(initCPM, 0.0f, MAX_BUY_CPM, 0.1f);
      JSpinner cpmSpinner  = new JSpinner(myCPMModel);
      cpmSpinner.addChangeListener(new ChangeListener() {
          public void stateChanged(ChangeEvent ev) {
              // Set impression goals based on CPM.
              CampaignBuy buy = myGrid.adBuy();
              float cpm = myCPMModel.getNumber().floatValue();
              Money buyBudget = buy.getSpendingLimit();
              long imprGoal = AuctionUtils.calculateImpressionGoals(buyBudget, cpm);
              buy.setImpressionLimit(imprGoal);
              buy.setTargetCpm(cpm);
              myGrid.recreateSoup(); // Need to rebuild bids.
              myGrid.setConstraints(myViewConstraints);
              checkAuction();
          }
       });
      JPanel cpmPanel = createLabeledBox("Change Target CPM", cpmSpinner);

      // Days of week.  
      // Note that the widget array uses the indices of the DayOfWeek.values() array.
      JPanel daysOfWeekPanel = new JPanel();
      final DayOfWeek[] allDays = DayOfWeek.values();
      myDaysOfWeekCxBox = new JCheckBox[allDays.length];
      daysOfWeekPanel.setBorder(new CompoundBorder(new LineBorder(Color.black), new EmptyBorder(GAP, GAP, GAP, GAP)));
      daysOfWeekPanel.setLayout(new BoxLayout(daysOfWeekPanel, BoxLayout.X_AXIS));
      daysOfWeekPanel.add(Box.createHorizontalStrut(GAP));
      daysOfWeekPanel.add(new JLabel("Days Of Week"));
      daysOfWeekPanel.add(Box.createHorizontalStrut(GAP));
      for (int i = 0; i < allDays.length; i++)
      {
          JCheckBox cb = new JCheckBox(allDays[i].name().substring(0, 2));
          daysOfWeekPanel.add(cb);
          daysOfWeekPanel.add(Box.createHorizontalStrut(GAP));
          cb.addActionListener(new ActionListener() {
              public void actionPerformed(ActionEvent ev) {
            	  setDaysOfWeek();
              }
          });
          myDaysOfWeekCxBox[i] = cb;
      }
      Set<DayOfWeek> days = myGrid.adBuy().getDaysOfWeek();
      if (days != null)
      {
    	  for (int i = 0; i < allDays.length; i++)
    	  {
    		  if (days.contains(allDays[i]))
    		  {
    			  myDaysOfWeekCxBox[i].setSelected(true);
    		  }
    	  }
      }

      // Grid Labels
      myTotalViewsLabel = createInfoLabel(TOTAL_VIEWS_LABEL);
      myCpmLabel = createInfoLabel(CPM_LABEL);
      myTotalCostLabel = createInfoLabel(TOTAL_COST_LABEL);
      myBLEffLabel = createInfoLabel(BL_EFF_LABEL);
      myResultEffLabel = createInfoLabel(RESULT_EFF_LABEL);
      
      // View constraint checkboxes
      myIsInProgCxBox = new JCheckBox("In Program?");
      myIsInProgCxBox.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev)
         {
            if (myIsInProgCxBox.isSelected()) 
            {
               myViewConstraints = myViewConstraints.plusConstraint(IN_PROGRAM);
            } else {
               myViewConstraints = myViewConstraints.minusConstraint(IN_PROGRAM);
            }
            myGrid.setConstraints(myViewConstraints);
            updateCheckboxes();
         }
      });

      myIsAuctionWinCxBox = new JCheckBox("Auction win?");
      myIsAuctionWinCxBox.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev)
         {
            if (myIsAuctionWinCxBox.isSelected()) 
            {
               myViewConstraints = myViewConstraints.plusConstraint(AUCTION_WIN);
               myIsAutoAuction = true;
            } 
            else 
            {
               myViewConstraints = myViewConstraints.minusConstraint(AUCTION_WIN);
               myIsAutoAuction = false;
            }
            myGrid.setConstraints(myViewConstraints);
            updateCheckboxes();
            if (myIsAutoAuction) 
            {
                runAuction();
            }
         }
      });
      
      myIsInAudienceCxBox = new JCheckBox("In Audience?");
      myIsInAudienceCxBox.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev)
         {
            if (myIsInAudienceCxBox.isSelected())  
            {
               myViewConstraints = myViewConstraints.plusConstraint(IN_AUDIENCE);
            } else {
               myViewConstraints = myViewConstraints.minusConstraint(IN_AUDIENCE);
            }
            myGrid.setConstraints(myViewConstraints);
            updateCheckboxes();
            checkAuction();
         }
      });
      
      // Auction toggles (read only!)
      AuctionViewToggles curAt = myGrid.auctionConstraints();

      myUseBudgetCxBox = new JCheckBox("Use Budget?");
      myUseBudgetCxBox.setSelected(curAt.useBudget);
      myUseBudgetCxBox.setEnabled(false);
      
      myUseInProgCxBox = new JCheckBox("Use Program?");
      myUseInProgCxBox.setSelected(curAt.inProgram);
      myUseInProgCxBox.setEnabled(false);
      
      myUseInAudienceCxBox  = new JCheckBox("Use T.Audience?");
      myUseInAudienceCxBox.setSelected(curAt.inAudience);
      myUseInAudienceCxBox.setEnabled(false);
      
      myBypassChanBundlCxBox = new JCheckBox("Bypass Chan Bundling?");
      myBypassChanBundlCxBox.setSelected(false);
      myBypassChanBundlCxBox.addActionListener(new ActionListener() {
          public void actionPerformed(ActionEvent ev)
          {
              boolean doBypass = myBypassChanBundlCxBox.isSelected();
              myGrid.adBuy().setBypassChanBundling(doBypass);
              checkAuction();
          }
       });
      
      myPayMinRateCxBox = new JCheckBox("Pay Min Rate?");
      myPayMinRateCxBox.setSelected(false);
      myPayMinRateCxBox.addActionListener(new ActionListener() {
          public void actionPerformed(ActionEvent ev)
          {
              boolean payMin = myPayMinRateCxBox.isSelected();
              myGrid.adBuy().setPaysMinimumRate(payMin);
              checkAuction();
          }
       });
      
      JButton showSettings = new JButton("Auction Settings...");
      showSettings.addActionListener(new ActionListener() {
          public void actionPerformed(ActionEvent ev) {
              AuctionParamsUI settingsUI = new AuctionParamsUI(myGrid);
          }
       });
      
      // Auction button
      JButton auctionButton = new JButton("Run Auction");
      auctionButton.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev) {

         }
      });
      // Auction results button
      JButton auctionResultsButton = new JButton("Print Results");
      auctionResultsButton.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            StringWriter sw = new StringWriter();
            myGrid.currentAuctioneer().dumpDetails(new PrintWriter(sw));
            myJsonMonitor.showText(sw.toString());
         }
      });
      
      JPanel flagsPanel = new JPanel();
      flagsPanel.setLayout(new BoxLayout(flagsPanel, BoxLayout.X_AXIS));
      flagsPanel.add(myBypassChanBundlCxBox);
      flagsPanel.add(myPayMinRateCxBox);
      
      // Auction panel layout
      JPanel auctionButtonPanel = new JPanel();
      auctionButtonPanel.setLayout(new BoxLayout(auctionButtonPanel, BoxLayout.X_AXIS));
      auctionButtonPanel.setBorder(
            new TitledBorder(LineBorder.createBlackLineBorder(), "Auction"));
      auctionButtonPanel.add(auctionButton);
      auctionButtonPanel.add(Box.createHorizontalStrut(GAP));
      auctionButtonPanel.add(myUseBudgetCxBox);
      auctionButtonPanel.add(Box.createHorizontalStrut(GAP));
      auctionButtonPanel.add(myUseInAudienceCxBox);
      auctionButtonPanel.add(Box.createHorizontalStrut(GAP));
      auctionButtonPanel.add(myUseInProgCxBox);
      auctionButtonPanel.add(Box.createHorizontalGlue());
      auctionButtonPanel.add(auctionResultsButton);
      auctionButtonPanel.add(Box.createHorizontalStrut(GAP));
      
      // View toggle layout
      JPanel viewTogglePanel = new JPanel();
      viewTogglePanel.setLayout(new BoxLayout(viewTogglePanel, BoxLayout.X_AXIS));
      viewTogglePanel.setBorder(
            new TitledBorder(LineBorder.createBlackLineBorder(), "View"));
      viewTogglePanel.add(myIsAuctionWinCxBox);
      viewTogglePanel.add(Box.createHorizontalStrut(20));
      viewTogglePanel.add(myIsInAudienceCxBox);
      viewTogglePanel.add(Box.createHorizontalStrut(6));
      viewTogglePanel.add(myIsInProgCxBox);
      viewTogglePanel.add(Box.createHorizontalGlue());
      
      // Buy attribute panel layout
      JPanel buyAttrPanel = new JPanel();
      buyAttrPanel.setBorder(
              new TitledBorder(LineBorder.createBlackLineBorder(), "Buy Attributes"));
      buyAttrPanel.setLayout(new GridLayout(4, 2, GAP*4, GAP*4));
      buyAttrPanel.add(targAudButton);
      buyAttrPanel.add(budgetPanel);
      buyAttrPanel.add(inProgramButton);
      buyAttrPanel.add(dateChooserPanel);
      buyAttrPanel.add(daysOfWeekPanel);
      buyAttrPanel.add(cpmPanel);
      buyAttrPanel.add(new JLabel()); // XXX Placeholder
      buyAttrPanel.add(flagsPanel);
      
      // Other stuff
      JPanel buttonPanel = new JPanel();
      buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
      buttonPanel.setBorder(new EmptyBorder(GAP, GAP, GAP, GAP));

      buttonPanel.add(Box.createHorizontalGlue());
      buttonPanel.add(myTotalViewsLabel);
      buttonPanel.add(Box.createHorizontalStrut(GAP));
      buttonPanel.add(myCpmLabel);
      buttonPanel.add(Box.createHorizontalStrut(GAP));
      buttonPanel.add(myTotalCostLabel);
      buttonPanel.add(Box.createHorizontalStrut(GAP));
      buttonPanel.add(myBLEffLabel);
      buttonPanel.add(Box.createHorizontalStrut(GAP));
      buttonPanel.add(myResultEffLabel);
      buttonPanel.add(Box.createHorizontalGlue());
      buttonPanel.add(showSettings);
      buttonPanel.add(Box.createHorizontalStrut(GAP));
      buttonPanel.add(exitB);
      buttonPanel.add(Box.createHorizontalStrut(GAP));
      
      // Bottom panel
      JPanel bottomPanel = new JPanel();
      bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
      bottomPanel.add(auctionButtonPanel);
      bottomPanel.add(Box.createVerticalStrut(GAP));
      bottomPanel.add(viewTogglePanel);
      bottomPanel.add(Box.createVerticalStrut(GAP));
      bottomPanel.add(buyAttrPanel);
      bottomPanel.add(Box.createVerticalStrut(GAP));
      bottomPanel.add(buttonPanel);
      bottomPanel.add(Box.createVerticalStrut(GAP));

      JPanel titlePanel = new JPanel();
      titlePanel.setBackground(Color.yellow);
      myTitleLabel = new JLabel();
      titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.X_AXIS));
      titlePanel.add(Box.createHorizontalGlue());
      titlePanel.add(myTitleLabel);
      titlePanel.add(Box.createHorizontalGlue());
      refreshTitle();
      
      JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, scroller, bottomPanel);
      
      getContentPane().add(titlePanel, BorderLayout.NORTH);
      getContentPane().add(splitPane, BorderLayout.CENTER);

      // Capture window close events
      this.addWindowListener(new WindowAdapter() {
         public void windowClosing(WindowEvent ev){
            shutdown();
         }
      });
      
      myProgramListView = new ProgramListUI(grid);
      myJsonMonitor = new JsonMonitorUI(grid, table, myProgramListView);
      
      // Set checkboxes
      updateCheckboxes();
      
      setBounds(0, 0, GRID_VIEW_WIDTH, GRID_VIEW_HEIGHT);
      pack();
      setVisible(true);
   }
   /**
    * Creates the date chooser to allow the user to select
    * any day from the current start date to the Grid's end
    * date.
    * @return JComboBox component.
    */
   private JComboBox createDateChooser()
   {
      Calendar start = myGrid.startDate();
      Calendar end = myGrid.endDate();
      ArrayList<Calendar> calArr = new ArrayList<Calendar>();
      ArrayList<String> strArr = new ArrayList<String>();
      Calendar day = Calendar.getInstance(start.getTimeZone());
      SimpleDateFormat df = new SimpleDateFormat("EEE, MMM dd");
      df.setTimeZone(start.getTimeZone());
      day.setTime(start.getTime());
      while (day.before(end))
      {
         calArr.add(day);
         String s = df.format(day.getTime());
         System.out.println("Adding: " + GridUtils.formatFullDate(day));
         strArr.add(s);
         Calendar newday = Calendar.getInstance(start.getTimeZone());
         newday.setTime(day.getTime());
         newday.add(Calendar.DAY_OF_YEAR, 1);
         day = newday;
      }
      final Calendar[] days = calArr.toArray(new Calendar[]{});
      final String[] selList = strArr.toArray(new String[]{});
      final JComboBox dateChooser = new JComboBox(selList);
      dateChooser.setSelectedIndex(selList.length - 1);
      dateChooser.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev)
         {
            int indx = dateChooser.getSelectedIndex();
            Calendar end = days[indx];
            Calendar start = myGrid.startDate();
            System.out.println("Setting end to " + selList[indx]);
            myGrid.setDateRange(start, end);
         }
      });
      return dateChooser;
   }

   
   /**
    * Set the Zone ID for all avails.
    * Currently, this supports only one zone.
    * @param zId Zone ID
    */
   public void setZoneId(int zId)
   {
      myZoneId = zId;
   }
   
   /**
    * Create a panel with a label on the left and the component on the right.
    * @param label
    * @param comp
    * @return
    */
   private JPanel createLabeledBox(String label, JComponent comp)
   {
       JPanel p = new JPanel();
       JLabel labelPanel = new JLabel(label);
       p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
       p.add(labelPanel);
       p.add(Box.createHorizontalStrut(GAP));
       p.add(comp);
       return p;
   }
   
   /**
    * Create a boxed label.
    * @param label
    * @return
    */
   private JLabel createInfoLabel(String labelPrefix)
   {
       JLabel l = new JLabel(labelPrefix + "--");
       l.setOpaque(true);
       l.setBorder(new CompoundBorder(new LineBorder(Color.black), new EmptyBorder(GAP, GAP, GAP, GAP)));
       return l;
   }
   
   /**
    * Update our checkboxes to reflect what is set on the grid.
    */
   private void updateCheckboxes()
   {
      // Set view toggle check boxes per our grid settings.
      AdConstraintSet cset = myGrid.constraints();
      boolean viewAuctionWins = cset.isSet(AUCTION_WIN);
      myIsInAudienceCxBox.setSelected(cset.isSet(IN_AUDIENCE));
      myIsAuctionWinCxBox.setSelected(viewAuctionWins);
      myIsInProgCxBox.setSelected(cset.isSet(IN_PROGRAM));
      
      // Set Auction Toggle checkboxes per our grid's auction constraints.
      AuctionViewToggles curAt = myGrid.auctionConstraints();
      myUseInProgCxBox.setSelected(curAt.inProgram);
      myUseInAudienceCxBox.setSelected(curAt.inAudience);
      myUseBudgetCxBox.setSelected(viewAuctionWins);
      // Budget is separate
      myGrid.setAuctionConstraints(
            new AuctionViewToggles(curAt.inProgram, curAt.inAudience, curAt.usePlacement, viewAuctionWins));
      
      // Initialize buy values with current values.
      Money budget = myGrid.adBuy().getSpendingLimit();
      myBudgetModel.setValue(budget.dollarsOnly());
   }
   
   /**
    * Set the title of the window
    */
   public void refreshTitle()
   {
      StringBuffer sb = new StringBuffer();
      sb.append("AdBuy #");
      sb.append(myGrid.adBuyId() + ": ");
      sb.append(GridUtils.formatShortDate(myGrid.startDate()));
      sb.append(" to ");
      sb.append(GridUtils.formatShortDate(myGrid.endDate()));
      myTitleLabel.setText(sb.toString());
   }

   /**
    * Alert JTable model that the grid has changed.
    * Check busy status, title label, and set grid progress if appropriate.
    * @param ev PropertyChangeEvent to handle
    * @see PropertyChangeListener
    */
   public void propertyChange(PropertyChangeEvent ev)
   {
      myAdaptor.gridChanged();
      updateCheckboxes();
      refreshTitle();
      myTotalViewsLabel.setText(TOTAL_VIEWS_LABEL + String.format("%,d", myGrid.sumTotalViews(false)));
      myCpmLabel.setText(CPM_LABEL + String.format("$%.2f", myGrid.derivedCpm()));
      myTotalCostLabel.setText(TOTAL_COST_LABEL + String.format("%s", myGrid.totalCost().toString()));
      myBLEffLabel.setText(BL_EFF_LABEL + String.format("%d%%", (int)myGrid.getLastAuctionBaselineEff()));
	  long totalDigitalViews = myGrid.sumTotalViews(true);
	  long targetV = myGrid.sumTargetViews();
      int eff = GridUtils.calculateEfficiency(totalDigitalViews, targetV);
      myResultEffLabel.setText(RESULT_EFF_LABEL + String.format("%d%%", eff));
   }
   
   /**
    * Simulate a return from Local with set of multipliers.
    * Cycle through the set of supplied multipliers for all channels and hours.
    * @param multipliers
    * @param zoneId Zone ID of multipliers
    * @param orgId Organization ID of multipliers
    */
   public void setMultipliers(int[] multipliers, int orgId)
   {
      int chCount = myGrid.channelCount();
      int hourCount = 24 * 7;
      int unitCount = chCount * hourCount;
      short[] data = new short[unitCount * 4];
      ShortBuffer buf = ShortBuffer.wrap(data);
      int mIndex = 0;
      for (int ch = 0; ch < chCount; ch++)
      {
         AdBuySoup.CableChannel cc = myGrid.getChannelByIndex(ch);
         for (int hourOfWk = 0; hourOfWk < hourCount; hourOfWk++)
         {
            buf.put((short)cc.id());
            buf.put((short)myZoneId);
            buf.put((short)hourOfWk);
            buf.put((short)multipliers[mIndex++]);
            if (mIndex >= multipliers.length)
            {
               mIndex = 0;
            }
         }
      }
      buf.rewind();
      myGrid.setTargetMultipliers(orgId, buf.asReadOnlyBuffer(), unitCount);
   }
   
   private int[] generateMultipliers()
   {
      final int MULTIPLIER_COUNT = 10;
      final int MULTIPLIER_MIN = 20;
      final int MULTIPLIER_MAX = 100 - MULTIPLIER_MIN;
      int[] rtn = new int[MULTIPLIER_COUNT];
      for (int i = 0; i < MULTIPLIER_COUNT; i++)
      {
         rtn[i] = theRandomizer.nextInt(MULTIPLIER_MAX);
      }
      return rtn;
   }
   
   /**
    * Set the Days-of-week constraints on the AdBuy.
    * Forces a recreation of the soup.
    */
   private void setDaysOfWeek()
   {
	  DayOfWeek[] allDays = DayOfWeek.values();
 	  Set<DayOfWeek> days = new HashSet<DayOfWeek>();
	  for (int i = 0; i < allDays.length; i++)
	  {
		  if (myDaysOfWeekCxBox[i].isSelected())
		  {
			  days.add(allDays[i]);
		  }
	  }
	  myGrid.adBuy().setDaysOfWeek(days);
	  myGrid.recreateSoup();
	  checkAuction();
   }
   
   /**
    * Run an auction if auto-auction flag is set.
    */
   public void checkAuction()
   {
       if (myIsAutoAuction)
       {
           runAuction();
       }
   }
   
   public void runAuction()
   {
       myGrid.runAuction();
       System.out.println(myGrid.dumpStatistics());
   }
   /**
    * Get rid of this UI Frame.
    */
   public void shutdown()
   {
      //myProgramListView.shutdown();
      //setVisible(false);
      //myGrid.removePropertyChangeListener(this);
      //myViewConstraints = null;
      //dispose();
      System.exit(0);
   }

   /*********** Class Members ****************************/
   static final int GRID_VIEW_WIDTH = 300;
   static final int GRID_VIEW_HEIGHT = 300;
   static final int GAP = 5;
   
   static final int MAX_BUY_BUDGET = 1000000000;
   static final int MAX_BUY_CPM = Integer.MAX_VALUE;
   
   static final String BL_EFF_LABEL = "Baseline Eff: ";
   static final String RESULT_EFF_LABEL = "Result Eff: ";
   static final String TOTAL_COST_LABEL = "Total Cost: ";
   static final String TOTAL_VIEWS_LABEL = "Total Views: ";
   static final String CPM_LABEL = "CPM: ";
   
   static final java.util.Random theRandomizer = new java.util.Random();

   /************ Object members *************************/
   private GridTableAdapter myAdaptor;
   private MasterGrid myGrid;
   private int myZoneId = 1;
   private AdConstraintSet myViewConstraints = null;
   private ProgramListUI myProgramListView = null;
   private JsonMonitorUI myJsonMonitor = null;

   private JCheckBox myIsInProgCxBox = null;
   private JCheckBox myIsAuctionWinCxBox = null;
   private JCheckBox myIsInAudienceCxBox = null;
   private JCheckBox[] myDaysOfWeekCxBox = null;
   // Auction
   private JCheckBox myUseInProgCxBox = null;
   private JCheckBox myUseInAudienceCxBox = null;
   private JCheckBox myUseBudgetCxBox = null;
   private JCheckBox myBypassChanBundlCxBox = null;
   private JCheckBox myPayMinRateCxBox = null;
   private JLabel myTotalViewsLabel = null;
   private JLabel myCpmLabel = null;
   private JLabel myTotalCostLabel = null;
   private JLabel myBLEffLabel = null;
   private JLabel myResultEffLabel = null;
   private JLabel myTitleLabel = null;
   private boolean myIsAutoAuction = false;
   // Buy
   private SpinnerNumberModel myBudgetModel = null;
   private SpinnerNumberModel myCPMModel = null;

   /*********************************************************
    * INNER CLASSES
    ***********************************************************/
   /**
    * JTable Model for Grid.
    * Adapts the MasterGrid model to the JTable model.
    * Note that the columns of the table include a label
    * at index 0 and a totals at grid index c+1, and the rows
    * include a totals row at grid index r+1.
    */
   class GridTableAdapter extends AbstractTableModel
   {
      /*********** Class Members ***************/
      private static final String TOTALS_LABEL = "<html><b>Totals</html>";
      
      /******* Object members ******/
      private MasterGrid myGrid;
      
      /**
       * Constructor.
       * @param grid
       */
      public GridTableAdapter(MasterGrid grid)
      {
         super();
         myGrid = grid;
      }
      /**
       * @return count of columns
       * @see TableModel
       */
      public int getColumnCount()
      {
         // LABEL + data columns + TOTALS
         return myGrid.daypartSet().size() + 2;
      }
      
      /**
       * @param i index of column
       * @return Name of column
       * @see TableModel
       */
      public String getColumnName(int i)
      {
         if (i == 0) return "";
         if (i == getColumnCount() - 1) 
            return TOTALS_LABEL;
         i -= 1;
         CableChannel chan0 = myGrid.getChannelByIndex(0);
         DaypartCell cell = chan0.getDaypartCellByIndex(i);
         return cell.channelDaypart().name();
      }
      
      /**
       * @return Number of rows in table.
       * @see TableModel
       */
      public int getRowCount()
      {
         // data rows + TOTALS
         return myGrid.channelCount() + 1;
      }
      
      /**
       * Get cell value.
       * @param row row index
       * @param col column index
       * @return String of format 'targets (efficiency%)'
       * @see TableModel
       */
      public Object getValueAt(int row, int col)
      {
         int dayPartIndex = col - 1;
         int lastRow = myGrid.channelCount();
         // Name column
         if (col == 0) {
            if (row == lastRow) {
               return TOTALS_LABEL;
            }
            // Display name [id]
            CableChannel chan = myGrid.getChannelByIndex(row);
            String rStr = new String(chan.name() + "  [" + chan.id() + "]");
            return rStr;
         }
         // Channel totals column
         if (col == myGrid.daypartSet().size() + 1) {
            if (row == lastRow) {
               return "";
            }
            CableChannel chan = myGrid.getChannelByIndex(row);
            return chan.sumTotalViews(myGrid.constraints(), ImpressionType.ANALOG_DIGITAL);
         }
         // Daypart totals row
         if (row == lastRow) {
            return myGrid.sumTotalViewsForDaypart(dayPartIndex, ImpressionType.ANALOG_DIGITAL);
         }
         // Get target and calculate efficiency
         CableChannel chan = myGrid.getChannelByIndex(row);
         DaypartCell view = chan.getDaypartCellByIndex(dayPartIndex);
         AdConstraintSet cset = myGrid.constraints();
         long total = view.totalDigitalViews(cset);
         long target = view.targetViews(cset);
         // int eff = GridUtils.calculateEfficiency(total, target);
         CellValue cell = new CellValue(total, target);
         return cell;
      }
      
      /**
       * Notify the JTable that the MasterGrid has changed.
       */
      public void gridChanged()
      {
         fireTableDataChanged();
      }
   }
}

/**
 * Class used to pass cell values to the cell renderer.
 */
class CellValue
{
   /**
    * Constructor.
    * @param total
    * @param target
    */
   public CellValue(long total, long target)
   {
      myTotal = total;
      myTarget = target;
      myEfficiency = GridUtils.calculateEfficiency(total, target);
   }
   /*** @return total impression count */
   public long total() { return myTotal; }
   /** @return target impression count */
   public long target() { return myTarget; }
   /** @return percent efficiency (0 - 100) */
   public int efficiency() { return myEfficiency; }
   
   /********* OBJECT MEMBERS **********/
   private final long myTotal;
   private final long myTarget;
   private final int myEfficiency;
} // END CellValue

/**
 * Cell renderer that colorizes cell background based on efficiency.
 */
class GridCellRenderer extends DefaultTableCellRenderer
{
   /**
    * @return component used to render cell.
    * @see javax.swing.table.TableCellRenderer
    */
   public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
         boolean hasFocus, int row, int col)
   {
      Component c = null;
      if (value instanceof CellValue)
      {
         CellValue cv = (CellValue)value;
         JLabel label1 = new JLabel();
         int eff =  cv.efficiency(); 
         Color color = getEfficiencyColor(eff);
         if (isSelected)
         {
            color = Color.LIGHT_GRAY;
            label1.setForeground(Color.white);
         }
         String n1 = COUNT_FMT.format(cv.target());
         String n2 = COUNT_FMT.format(cv.total());
         String txt1 =  n1 + " / " + n2;
         label1.setText(txt1);
         label1.setOpaque(true);
         label1.setFont(CELL_FONT);
         label1.setBackground(color);
         c = label1;
      } 
      else
      {
         c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
      }
      return c;
   }
   
   /**
    * Simulate color gradation in cell UI.
    * @param eff Efficiency (0 - 100%)
    * @return Color associated with this efficiency
    */
   private Color getEfficiencyColor(int eff)
   {
      if (eff <= 0) eff = 0;
      // start with white (eff == 0)
      int g = 255;
      int r = 255;
      int b = 255;
      while (eff > 0)
      {
         // Lower green value half as fast as other 2 to darken cell.
         g -= 6;
         r -= 12;
         b -= 12;
         eff -= 10;
      }
      if (r < 0) r = 0;
      if (b < 0) b = 0;
      if (g < 0) g = 0;
      Color c = new Color(r, g, b);
      return c;
   }
   /************** CLASS MEMBERS ***********************************/
   static final Font CELL_FONT = new Font("Helvetica", Font.PLAIN, 10);
   static final DecimalFormat COUNT_FMT = new DecimalFormat("###,###,###,###");
}  // END GridCellRenderer

/**
 * UI for testing AdBuy MasterGrid Program List View.
 */
class ProgramListUI extends JFrame
{
   /**
    * Constructor
    * @param grid
    */
   public ProgramListUI(MasterGrid grid)
   {
      super("ProgramList View");
      myGrid = grid;
      myModel = new DefaultListModel();
      myList = new JList(myModel);
      myList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      myKeys = new ArrayList<String>();
      myLabel = new JLabel();
      myListType = GridFormatter.PListType.BY_PROGRAM_TITLE;
      
      JScrollPane scroller = new JScrollPane(myList);
      JPanel buttonPanel = new JPanel();
      int cols = (GridFormatter.PListType.values().length + 1) / 2;
      buttonPanel.setLayout(new GridLayout(2, cols));
      //buttonPanel.add(Box.createHorizontalGlue());
      //
      // For each list type, put a button that builds the group list
      //
      for (GridFormatter.PListType t : GridFormatter.PListType.values())
      {
         final GridFormatter.PListType ptype = t;
         JButton button = new JButton(ptype.name());
         button.setFont(BUTTON_FONT);
         button.addActionListener(new  ActionListener() {
            public void actionPerformed(ActionEvent ev) {
               try
               {
                  // Fill list with program group rows
                  JSONArray arr = GridFormatter.gridProgramListToJson(myGrid, ptype);
                  myModel.removeAllElements();
                  myKeys.clear();
                  int len = arr.length();
                  for (int i = 0; i < len; i++)
                  {
                     JSONObject hdr = arr.getJSONObject(i);
                     myModel.addElement(jsonRowToString(hdr));
                     myKeys.add((String)hdr.get(GridFormatter.PROGLIST_ID_KEY));
                  }
                  myLabel.setText("Programs for " + myGrid.adBuyId() + 
                        "(" + ptype.name() + " view)");
                  myList.firePropertyChange("all", 0, 1);
                  myListType = ptype;
               }
               catch (JSONException e)
               {
                  theLogger.error("GridFormatter error", e);
               }
            }
         });
         buttonPanel.add(button);
         //buttonPanel.add(Box.createHorizontalStrut(5));
      }
      JButton expandButton = new JButton("Expand");
      expandButton.addActionListener(new  ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            try
            {
               int sel = getSelectedIndex();
               String key = getSelectedKey(sel);
               JSONArray arr = GridFormatter.gridProgramListDetailsToJson(myGrid, myListType, key);
               int len = arr.length();
               for (int i = 0; i < len; i++)
               {
                  String progStr = jsonProgramToString(arr.getJSONObject(i));
                  int indx = sel + 1 + i;
                  myModel.insertElementAt(progStr, indx);
                  myKeys.add(indx, NO_KEY);
               }
            }
            catch (JSONException e)
            {
               theLogger.error("GridFormatter error", e);
            }
         }
      });
      buttonPanel.add(expandButton);
      buttonPanel.add(Box.createHorizontalStrut(5));
      
      getContentPane().add(myLabel, BorderLayout.NORTH);
      getContentPane().add(scroller, BorderLayout.CENTER);
      getContentPane().add(buttonPanel, BorderLayout.SOUTH);
      pack();
      setSize(PROG_UI_WIDTH, PROG_UI_HEIGHT);
      setLocation(PROG_UI_X, PROG_UI_Y);
      setVisible(true);
   }
   
   /** @return currently set list type */
   public GridFormatter.PListType getListType()
   {
      return myListType;
   }
   
   /** @return the selected index or -1 if nothing selected */
   public int getSelectedIndex()
   {
      int sel = myList.getSelectedIndex();
      return sel;
   }
   
   /** @return the key currently selected or NO_KEY if none */
   public String getSelectedKey()
   {
      return getSelectedKey(getSelectedIndex());
   }
   
   /** @return the key at the selected index or NO_KEY if invalid */
   public String getSelectedKey(int sel)
   {
      if (sel < 0)
      {
         return NO_KEY;
      }
      String key = myKeys.get(sel);
      if (key == null || key.equals(NO_KEY))
      {
         return NO_KEY;
      }
      return key;
   }
   
   /**
    * Convert a JSON Program list view row to a single string.
    * @param hdr JSONObject of program list row.
    * @return String representation.
    * @throws JSONException 
    */
   private String jsonRowToString(JSONObject hdr) throws JSONException
   {
      StringBuffer sb = new StringBuffer();
      sb.append(hdr.get(GridFormatter.PROGLIST_NAME_KEY));
      sb.append(" : ");
      String s = theCountFormatter.format(hdr.get(GridFormatter.PROGLIST_PROGRAM_COUNT_KEY));
      sb.append(s);
      sb.append(" programs");
      sb.append(" : ");
      s = theCountFormatter.format(hdr.get(GridFormatter.TOTAL_VIEWS_KEY));
      sb.append(s);
      sb.append(" total views.");
      return sb.toString();
   }
   
   /**
    * Convert a JSON Program list view detail to a single string.
    * @param hdr JSONObject of program list detail.
    * @return String representation.
    * @throws JSONException 
    */
   private String jsonProgramToString(JSONObject hdr) throws JSONException
   {
      StringBuffer sb = new StringBuffer(" >> ");
      sb.append(hdr.get(GridFormatter.PROGRAM_TITLE_KEY));
      sb.append(" : ");
      sb.append(hdr.get(GridFormatter.CHANNEL_NAME_KEY));
      sb.append(" : ");
      sb.append(hdr.get(GridFormatter.PROGRAM_CATEGORY_KEY));
      sb.append(" : ");
      sb.append(hdr.get(GridFormatter.DATE_KEY));
      sb.append(" : ");
      sb.append(hdr.get(GridFormatter.TIME_OF_DAY_KEY));
      sb.append(" : ");
      sb.append(hdr.get(GridFormatter.PROGRAM_DURATION_KEY));
      sb.append(" min : ");
      sb.append(hdr.get(GridFormatter.MARKET_NAME_KEY));
      sb.append(" : ");
      sb.append(hdr.get(GridFormatter.TARGET_VIEWS_KEY));
      sb.append("/");
      sb.append(hdr.get(GridFormatter.TOTAL_VIEWS_KEY));
      sb.append(" views");
      sb.append(" #");
      sb.append(hdr.get(GridFormatter.PROGRAM_ID_KEY));
      sb.append("-");
      sb.append(hdr.get(GridFormatter.PROGRAM_VIEW_ID_KEY));
      
      return sb.toString();
   }
   
   /**
    * Shutdown this UI.
    */
   public void shutdown()
   {
      this.dispose();
   }
   
   /****** Class Members *****************/
   static final int PROG_UI_WIDTH  = 750;
   static final int PROG_UI_HEIGHT = 350;
   static final int PROG_UI_X = 25;
   static final int PROG_UI_Y = 25;
   
   
   /** Key selection for selecting a program detail row */
   public static final String NO_KEY = "NOKEY";
   static Font BUTTON_FONT = new Font("Helvetica", Font.PLAIN, 10);
   /** Formatter for impression counts */
   private static final DecimalFormat theCountFormatter = 
      new DecimalFormat("###,###,###,###");
   
   /******* Object Members *********/
   private MasterGrid myGrid = null;
   private JList myList = null;
   private JLabel myLabel = null;
   private DefaultListModel myModel = null;
   private List<String> myKeys = null;
   private GridFormatter.PListType myListType = null;

   // Logging
   private static Logger theLogger = Logger.getLogger(ProgramListUI.class);
   
   
} // END ProgramListUI

/**
 * Class simulating setting IN PROGRAM.
 * Simply produces a fixed success rate.
 */
class InProgramSetter implements CriteriaCheckerIFace
{
   /**
    * Constructor
    * @param successRate Number of 
    */
   public InProgramSetter(int successRate)
   {
      mySuccessRate = successRate;
   }
   
   /** Evaluate the given indexed attribute object against the
    *  criteria mechanism implemented by the class that implements
    *  this interface.
    *  @param indexedObject An IndexedAttributeObject against which
    *  the criteria should be checked.
    *  @return boolean indicator of whether or not the object meets the
    *  criteria.  True indicates that it meets the criteria.
    */
   public boolean evaluate (IndexedAttributeObject indexedObject)
   {
      myCount++;
      if (mySuccessRate == myCount)
      {
         myCount = 0;
         return true;
      }
      return false;
   }
   
   /** Return a String representation of the object.
    *  @param level how many levels into the nested list of target audience is this criterion.
    *  @return A String representation of the object.
    */   
   public String dump(int level)
   {
      // ignore level, it's not needed in this context
      return ("InProgramSetter count=" + myCount + " successRate=" + mySuccessRate);
   }   
   
   
   private int myCount = 0;
   private int mySuccessRate = 0;
} // END InProgramSetter


/**
 * UI for looking at text-formatted grid data.
 * Specifically, debug and JSON output.
 */
class JsonMonitorUI extends JFrame
{
   /**
    * Constructor.
    * @param grid MasterGrid
    * @param table Table to get selected cell from.
    */
   public JsonMonitorUI(MasterGrid grid, JTable cellTable, ProgramListUI progListView)
   {
      super("Text Monitor");
      myGrid = grid;
      myCellTable = cellTable;
      myProgramListUI = progListView;
      JPanel allContent = new JPanel();
      myTextDisplay = new JTextArea(JSON_TEXT_ROWS, JSON_TEXT_COLS);
      myTextDisplay.setFont(TEXT_FONT);
      myScroller = new JScrollPane(myTextDisplay);
      myScroller.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      myScroller.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

      // Show JSON for grid info
      JButton gridInfoButton = new JButton("Grid Info");
      gridInfoButton.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            try
            {
               String s = GridFormatter.gridInfoToJson(myGrid).toString(3);
               showText(s);
            }
            catch (JSONException e)
            {
               theLogger.error("GridFormatter error", e);
            }
         }
      });
      
      // Show JSON for grid data for cell
      JButton gridDataButton = new JButton("Cell View");
      gridDataButton.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            try
            {
               String s = GridFormatter.gridCellViewToJson(myGrid).toString(2);
               showText(s);
            }
            catch (JSONException e)
            {
               theLogger.error("GridFormatter error", e);
            }
         }
      });

      // Show JSON for cell details of a cell
      JButton detailsButton = new JButton("Cell Details");
      detailsButton.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            try
            {
               Point cell = getSelectedCell();
               if (cell == null) return;
               CableChannel chan = myGrid.getChannelByIndex(cell.y);
               DaypartCell dpv = chan.getDaypartCellByIndex(cell.x);
               //ChannelSchedule aff = myGrid.getChannelByIndex(cell.y);
               //ChannelDaypartView dpv = aff.getDaypartByIndex(cell.x);
               StringBuffer sb = new StringBuffer("Adbuy #");
               sb.append(myGrid.adBuyId());
               sb.append(" [" + cell.y);
               sb.append("," + cell.x);
               sb.append("] " + chan.name() + " at " + dpv.daypart().title() + "\n");
               String s = GridFormatter.cellDetailToJson(
                     myGrid, cell.y, cell.x).toString(3);
               sb.append(s);
               showText(sb.toString());
            }
            catch (JSONException e)
            {
               theLogger.error("GridFormatter error", e);
            }
         }
      });
      
      // Show JSON for grid program view.
      JButton programButton = new JButton("Program List");
      programButton.addActionListener(new  ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            try
            {
               GridFormatter.PListType type = myProgramListUI.getListType();
               String key = myProgramListUI.getSelectedKey();
               String s = null;
               //
               // If we have not selected any row in the Prog List View,
               // return the JSON for the view.  Otherwise, return the
               // JSON for the selected program group.
               //
               if (key == ProgramListUI.NO_KEY)
               {
                  s = GridFormatter.gridProgramViewToJson(myGrid, type).toString(2);
               }
               else 
               {
                  s = GridFormatter.gridProgramListDetailsToJson(myGrid, type, key).toString(2);
               }
               showText(s);
            }
            catch (JSONException e)
            {
               theLogger.error("GridFormatter error", e);
            }
         }
      });
      
      // Show JSON for grid program view.
      JButton programDebugButton = new JButton("Program Debug");
      programDebugButton.addActionListener(new  ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            GridFormatter.PListType type = myProgramListUI.getListType();
            String key = myProgramListUI.getSelectedKey();
            String name = GridFormatter.getMappedString(type, key);
            String s = null;
            //
            // If we have not selected any row in the Prog List View,
            // return.  Otherwise, return the
            // JSON for the selected program group.
            //
            if (key == ProgramListUI.NO_KEY)
            {
               s = "Select a Program List View item!";
            }
            else 
            {
               List<ProgramView> plist = GridFormatter.gridProgramListDetails(myGrid, type, key);
               StringBuffer sb = new StringBuffer("PROGRAM LIST group " + type + " : " + name + "\n");
               for (ProgramView p : plist)
               {
                  for (Integer orgId : p.getOrgIds())
                  {
                     sb.append("===== Program #" + p.programId() + " , Org #" + orgId + " ====\n");
                     List<BreakView> breakList = p.getBreaks(orgId);
                     printBreakViews(breakList, sb);
                  }
               }
               s = sb.toString();
            }
            showText(s);
         }
      });
      
      // Show all break debug info for a cell
      JButton statsButton = new JButton("Cell Debug");
      statsButton.addActionListener(new ActionListener() {
         public void actionPerformed(ActionEvent ev) {
            Point cell = getSelectedCell();
            if (cell == null) return;
            // Print out all breaks in this cell
            CableChannel chan = myGrid.getChannelByIndex(cell.y);
            DaypartCell dpv = chan.getDaypartCellByIndex(cell.x);
            long totalADViews = dpv.totalADViews(AdConstraintSet.DEFAULT);
            long totalDigitalViews = dpv.totalDigitalViews(AdConstraintSet.DEFAULT);
            List<BreakShadow> breakList = dpv.getAllBreaks();
            StringBuffer sb = new StringBuffer("CELL BREAK LIST [" + cell.y + "," + cell.x + "] (");
            sb.append(chan.name() + ",");
            sb.append(dpv.daypart().title() + ")\n");
            sb.append("TotalAD: " + totalADViews + " TotalDigital: " + totalDigitalViews + "\n");
            printBreaks(breakList, sb);
            int crCount = countCR(sb.toString());
            showText(sb.toString());
            myTextDisplay.setRows(crCount);
         }
      });
      
      
      JPanel buttonPanel = new JPanel();
      buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
      buttonPanel.add(Box.createHorizontalGlue());
      buttonPanel.add(statsButton);
      buttonPanel.add(Box.createHorizontalStrut(3));
      buttonPanel.add(gridDataButton);
      buttonPanel.add(Box.createHorizontalStrut(3));
      buttonPanel.add(gridInfoButton);
      buttonPanel.add(Box.createHorizontalStrut(3));
      buttonPanel.add(detailsButton);
      buttonPanel.add(Box.createHorizontalStrut(3));
      buttonPanel.add(programButton);
      buttonPanel.add(Box.createHorizontalStrut(3));
      buttonPanel.add(programDebugButton);
      buttonPanel.add(Box.createHorizontalStrut(3));

      allContent.setLayout(new BoxLayout(allContent, BoxLayout.Y_AXIS));
      //allContent.add(Box.createVerticalGlue());
      allContent.add(myScroller);
      allContent.add(Box.createVerticalStrut(10));
      allContent.add(buttonPanel);
      allContent.add(Box.createVerticalStrut(10));
      
      getContentPane().add(allContent, BorderLayout.CENTER);
      pack();
      setVisible(true);
   }
   
   /**
    * Show a string in the display.
    * @param s
    */
   public void showText(String s)
   {
       myTextDisplay.setText(s);
       myTextDisplay.setCaretPosition(0);
   }
   
   /**
    * Print the contents of the list of breaks into a String Buffer.
    * @param breakList List of BreakShadows
    * @param sb StringBuffer.
    */
   private void printBreaks(List<BreakShadow> breakList, StringBuffer sb)
   {
      // Sort by containing program for easier comparison
      Collections.sort(breakList, new Comparator<BreakShadow>() {
         public int compare(BreakShadow b1, BreakShadow b2) {
            int p1 = b1.breakView().program().programId();
            int p2 = b2.breakView().program().programId();
            return p2 - p1;
         }});
      for (BreakShadow b : breakList)
      {
         sb.append(b.toString());
         sb.append("\n");
      }
   }
   
   /**
    * Print the contents of the list of breaks into a String Buffer.
    * @param breakList List of BreakViews
    * @param sb StringBuffer.
    */
   private void printBreakViews(List<BreakView> breakList, StringBuffer sb)
   {
      // Sort by containing program for easier comparison
      Collections.sort(breakList, new Comparator<BreakView>() {
         public int compare(BreakView b1, BreakView b2) {
            int p1 = b1.program().programId();
            int p2 = b2.program().programId();
            return p2 - p1;
         }});
      for (BreakView b : breakList)
      {
         sb.append(b.toString());
         sb.append("\n");
      }
   }
   
   /**
    * @param s String to count
    * @return number of carriage returns in a String.
    */
   private static int countCR(String s)
   {
      if (s == null || s.length() == 0)
         return 0;
      int start = s.indexOf("\n");
      int count = 0;
      while (start > 0)
      {
         start = s.indexOf("\n", start + 1);
         count++;
      }
      return count;
   }
  
   /***
    * Get the selected cell and return as a Point object.
    * Check for invalid values and give feedback.
    * @return Point of selection on MasterGrid viewer.
    */
   Point getSelectedCell()
   {
      int row = myCellTable.getSelectedRow();
      int col = myCellTable.getSelectedColumn();
      if (row < 0 || col < 0)
      {
         myTextDisplay.setText("Select cell in MasterGrid viewer!");
         return null;
      }
      if (row == (myCellTable.getRowCount() - 1) || 
          col == 0 || col == (myCellTable.getColumnCount() - 1))
      {
         myTextDisplay.setText("Select valid data cell in MasterGrid viewer!");
         return null;
      }
      return new Point(col - 1, row);
   }
  

   /****** Class Members *****************/
   static int JSON_TEXT_ROWS = 16;
   static int JSON_TEXT_COLS = 45;
   static Font TEXT_FONT = new Font("Courier", Font.PLAIN, 12);
   
   /****** Object members ****************/
   private MasterGrid myGrid = null;
   private JTextArea myTextDisplay = null;
   private JScrollPane myScroller = null;
   private JTable myCellTable = null;
   private ProgramListUI myProgramListUI = null;

   // Logging
   private static Logger theLogger = Logger.getLogger(JsonMonitorUI.class);
}

/** 
 * Convenience label class 
 */
class ColoredLabel extends JLabel {
    public ColoredLabel(String txt)
    {
        this(Color.WHITE, txt);
    }
    public ColoredLabel(Color bg, String txt)
    {
        super(txt);
        this.setOpaque(true);
        this.setBackground(bg);
        this.setBorder(new BevelBorder(BevelBorder.RAISED));
        this.setHorizontalAlignment(SwingConstants.CENTER);
    }
}

/**
 * UI for displaying current Auction Parameters.
 */
class AuctionParamsUI extends JFrame
{
   /**
    * Constructor
    * @param grid
    */
   public AuctionParamsUI(MasterGrid grid)
   {
       super("Auction System Parameters");
       mySettings = grid.getSpotScheduleCache().getAuctionSettings();
       JComponent passes = createAuctionPassDisplay(grid);
       JPanel container = new JPanel();
       container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
       container.add(Box.createVerticalStrut(GAP));
       container.add(passes);
       container.add(Box.createVerticalStrut(GAP));
       container.add(createSettingsPanel(mySettings));
       container.add(Box.createVerticalGlue());
       getContentPane().add(container, BorderLayout.CENTER);
       
       setBounds(10, 10, INIT_WIDTH, INIT_HEIGHT);
       pack();
       setVisible(true);
   }
   
   /**
    * Create the panel with all auction settings.
    * @param as
    * @return
    */
   public JPanel createSettingsPanel(AuctionSettings as)
   {
       JPanel container = new JPanel();
       container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
       container.add(Box.createVerticalStrut(GAP));
       container.add(setting(SystemParameter.Key.AUCTION_CPM_CLEARANCE_THRESHOLD, as.cpmThresholdPct()));
       container.add(Box.createVerticalStrut(GAP));
       container.add(setting(SystemParameter.Key.AUCTION_EFFICENCY_CLEARANCE_THRESHOLD, as.efficiencyThresholdPct()));
       container.add(Box.createVerticalStrut(GAP));
       container.add(setting(SystemParameter.Key.AUCTION_MAX_CAMPAIGN_LIMIT_PCT, as.maxCampaignBudgetPct()));
       container.add(Box.createVerticalStrut(GAP));
       container.add(setting(SystemParameter.Key.AUCTION_WIN_MARGIN, (int)as.minAuctionWinMargin()));
       container.add(Box.createVerticalStrut(GAP));
//       container.add(setting(SystemParameter.Key.AUCTION_NAVIC_COMMISSION_PCT, (int)as.navicCommissionPct()));
//       container.add(Box.createVerticalStrut(GAP));
       return container;
   }
   
   /**
    * Create a panel for an auction system param.
    * @param key
    * @param val
    * @return
    */
   private JPanel setting(SystemParameter.Key key, Integer val)
   {
       JPanel container = new JPanel();
       container.setLayout(new GridLayout(1,2));
       container.add(new ColoredLabel(key.name() + ": "));
       container.add(new ColoredLabel(Integer.toString(val)));
       return container;
   }
   
   
   /**
    * Creates a chooser that displayes the number
    * of auction passes.
    * @return JComboBox component.
    */
   private JComponent createAuctionPassDisplay(MasterGrid grid)
   {
       JPanel inner = new JPanel();
       JPanel outer = new JPanel();
       outer.setLayout(new BoxLayout(outer, BoxLayout.X_AXIS));
       
       List<AuctionPass> plist = grid.getSpotScheduleCache().getAuctionPassList();
       inner.setLayout(new GridLayout(plist.size() + 1, 2));
       inner.setBorder(new LineBorder(Color.BLACK, 1));
       inner.add(new ColoredLabel("Priority"));
       inner.add(new ColoredLabel("Rate"));
       for (AuctionPass p : plist)
       {
           String ps = Integer.toString(p.priority());
           String rs = p.priorityAdjustPercent() + "%";
           inner.add(new ColoredLabel(ps));
           inner.add(new ColoredLabel(rs));
       }
       //JScrollPane tblScroller = new JScrollPane(table);
       outer.add(Box.createHorizontalStrut(GAP));
       outer.add(new JLabel("Auction Passes:"));
       outer.add(Box.createHorizontalStrut(GAP));
       outer.add(inner);
       outer.add(Box.createHorizontalGlue());
       return outer;
   }
   
   private AuctionSettings mySettings;
   private static final int GAP = AdBuyGridUI.GAP;
   private static final int INIT_WIDTH = 100;
   private static final int INIT_HEIGHT = 100;
}




