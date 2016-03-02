# Navic Ad Exchange project

Navic's AdExchange product was an auctioning engine that matched television advertising slots with advertiser campaigns.  It included a facility to simulate effects of changing campaign buy attributes (length of buy, channels targeted, etc.) and view the effects in real time by running a simulated auction and displaying the results, which could be drilled down and examined.

## Money
One class I wrote was the ```Money``` class to fill the lack (at the time) of Java support for financial amounts.  Floating point numbers are notoriously inaccurate at representing money, so I designed a class.  Since then, a (much more complex) JSR has been through the review process to provide such support.
