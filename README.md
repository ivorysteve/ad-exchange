# Advertising Exchange project

This production-grade code featured an auctioning engine that matched television advertising slots with advertiser campaigns.  It included a facility to simulate effects of changing campaign buy attributes (length of buy, channels targeted, etc.) and view the effects in real time by running a simulated auction and displaying the results, which could be drilled down and examined.

This repository is intended to present a collection of sample work, not as a buildable applicatoin.

## Auctioneer
The ```Auctioneer``` class originated as a (huge and unwieldy) database stored procedure.  It implements the core functionality of an actual auction, its algorithm, constraints, inputs, and outputs.

## AuctionBudget
The ```AuctionBudget``  class is intended to track campaign, per-channel, per-daypart, weekly, and daily budget constraints during the course of an auction.

## Money
The ```Money``` class was intended to fill the lack (at the time) of Java support for financial amounts.  Floating point numbers are notoriously inaccurate at representing money, so I designed a class.  Since then, a (much more complex) library (JSR-354) has been through the review process to provide such support.

## MasterGrid
The ```MasterGrid``` class aggregates auction information about available spots for a single campaign buy across all ___dayparts___ for a date range over a generic 24-hour network broadcast for a list of network channels.
 This class is intended to act as the Model for an MVC web interface View, where the grid is a table of channel rows and daypart columns.

## AdBuyGridUI
The ```AdBuyGridUI```  Class is a Swing-based desktop test app intended to allow viewing of ```MasterGrid``` data and behavior before the web interface had been finished.  It also tracks placement adjacency rules for each commercial break, preventing an ad from being broadcast with a direct competitor during the same commercial break.
