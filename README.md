# Advertising Auction project

This production-grade code featured an auctioning engine that matched up television advertising 30-second available ``Spots`` with advertiser ``CampaignBuy`` objects.  It implements a "Second Price Auction" model of auctioning. The class supports a facility to simulate effects of changing the campaign buy attributes (date range of buy, channels & dayparts targeted, etc.) and view the effects in real time by running a simulated auction and displaying the results immediately, which could then be drilled down and examined (to the detail level of the specific programs).

This repository is intended to present a limited collection of sample production work, not as a buildable application.

## Auctioneer
The ``Auctioneer`` class originated as a (huge and unwieldy) database stored procedure.  It implements the core functionality of an actual auction, its algorithm, constraints, inputs, and outputs.  Over a period of many releases, it became fairly complex due to layers of additional features (such as mirrored bids, segmented bids, and multi-pass auctions).

## AuctionBudget
The ``AuctionBudget``  class is intended to track spending and constraints on a per-campaign-buy, per-channel, per-daypart, weekly, and daily bases during the course of an auction.

## Money
The ``Money`` class was intended to fill the lack (at the time) of Java support for representing financial amounts.  Floating point numbers are notoriously inaccurate at representing money, so I designed this class.  Since then, a (much more complex) library (JSR-354) has been through the JCP review process to provide such support.

## MasterGrid
The ``MasterGrid`` class aggregates simulated auction information about available spots for a single campaign buy across all ___dayparts___ for a date range over a generic 24-hour network broadcast for a list of network channels.
 This class is intended to act as the Model for an MVC web interface View, where the grid is displayed as a table of channel rows and daypart columns.

## AdBuyGridUI
The ``AdBuyGridUI`` class is a Swing-based desktop test app intended to allow viewing of ``MasterGrid`` data and behavior before the web interface had been finished.  It also tracks placement adjacency rules for each commercial break, preventing an ad from being broadcast with a direct competitor during the same commercial break.
