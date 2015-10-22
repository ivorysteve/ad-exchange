/********************************************************
* *
* Copyright (C) Microsoft. All rights reserved. *
* *
********************************************************/

package AdExchange.Core;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;
import java.util.Currency;

/**
 * Simple Money value class that represents an amount of American dollars as the
 * number of cents. The class is immutable, and so thread-safe. Arithmetic
 * operations (such as add, subtract, etc.) always return a new, (guaranteed
 * non-null) Money object containing the new value, instead of changing its own
 * internal representation.
 * 
 * Only US currency (dollars) are supported by this class. For String
 * representations, the Currency for Locale US is used. The Currency Symbol is
 * the Dollar Sign ("$"). Negative amounts are enclosed in parentheses. A
 * Decimal Point (".") separates the dollars and cents, and a comma (",")
 * separates each group of 3 consecutive digits in the dollar amount.
 */
public final class Money 
implements Cloneable, Comparable<Money>, Serializable
{
   /**
    * Constructor specifying dollars and cents as separate values. The dollars value
    * is multiplied by 100 to get the number of cents. This value is then added to
    * the cents value to get the total amount.
    * 
    * @param dollars Number of dollars in this monetary value.
    * @param cents Number of cents in this monetary value.
    */
   public Money(long dollars, int cents)
   {
      myCents = calcCents(dollars, cents);
   }

   /**
    * Constructor specifying a dollar floating point value. Note that calling
    * this constructor with an integer or long value indicating whole dollars
    * will automatically convert correctly.
    * 
    * @param dollarsCents A Double representation of this monetary value, with
    *        the integer portion representing dollars, and the decimal portion
    *        representing cents. Cents will be rounded to the nearest penny.
    */
   public Money(double dollarsCents)
   {
      myCents = calcCents(dollarsCents);
   }
   
   /**
    * Constructor specifying a BigDecimal
	* @param initialValue The initial BigDecial value for this Money object
	*/
   public Money(BigDecimal initialValue)
   {
	   // convert the dollars and cents floating point to pure cents (pennies)
	   myCents = initialValue.multiply(BIG_DECIMAL_100).longValue();
   }

   /**
    * Factory method specifying the monetary value in String form.
    * 
    * @param dollarsCents String representation of a monetary value for US
    *        dollars. The Currency Symbol (the Dollar Sign ("$")) must precede
    *        the value. Negative amounts are enclosed in parentheses
    *        ("($34.89)"). A Decimal Point (".") separates the dollars and
    *        cents, and a comma (",") separates each group of 3 consecutive
    *        digits in the dollar amount.
    * @see java.util.DecimalFormat
    * @throws IllegalArgumentException if string cannot be parsed.
    */
   public static Money newFromString(String dollarsCents)
   {
      try
      {
         Number value = getCurrencyFormat().parse(dollarsCents);
         long cents = calcCents(value.doubleValue());
         return new Money(cents);
      }
      catch (ParseException pex)
      {
         throw new IllegalArgumentException("Invalid currency format.");
      }
   }

   /**
    * Factory method specifying total number of cents in this monetary value.
    * 
    * @param cents Total number of cents in this monetary value.
    */
   public static Money newFromPennies(long cents)
   {
      return new Money(cents);
   }

   /**
    * Constructor specifying total number of cents in this value. Note that this
    * method is private because it may be misleading: calling it with an integer
    * argument intending it to be a dollar value, for example, would result in
    * an incorrect monetary value.
    * 
    * @see newFromPennies
    * @param cents Total number of cents in this monetary value.
    */
   private Money(long cents)
   {
      myCents = cents;
   }

   /**
    * Clone this monetary value.
    * 
    * @return new Money object with same monetary value.
    * @see Cloneable
    */
   @Override
   public Object clone()
   {
      return createCopy();
   }

   /**
    * Clone this monetary value (type-safe version).
    * 
    * @return new Money object with same monetary value.
    */
   public Money createCopy()
   {
      return new Money(myCents);
   }

   /**
    * Total value of this Money object in cents.
    * 
    * @return Total amount of cents in this Money value.
    */
   public long valueInCents()
   {
      return myCents;
   }

   /**
    * The dollar component of this Money value. If the Money value is 
    * negative, this will return a negative number.
    * 
    * @return Number of dollars that make up this value.
    */
   public long dollarsOnly()
   {
      return (int) (myCents / CENTS_PER_DOLLAR);
   }

   /**
    * The cents component of this Money value. If the Money value is 
    * negative, this will return a negative number.
    * 
    * @return Number of cents (value modulo 100).
    */
   public int centsOnly()
   {
      return (int) (myCents % CENTS_PER_DOLLAR);
   }

   /************ ARITHMETIC **********************/

   /**
    * Returns the absolute monetary value. A positive value is returned,
    * irrespective of whether the monetary value is positive or negative.
    * 
    * @return A new Money object representing the result
    */
   public Money abs()
   {
      return new Money(Math.abs(myCents));
   }

   /**
    * Returns the monetary value whose sign is reversed.
    * 
    * @return A new Money object representing the result.
    */
   public Money negate()
   {
      return new Money(-(myCents));
   }

   /**
    * Add an amount of Money to this value and return the result.
    * 
    * @param m Amount to add.
    * @return Result in the form of a Money object.
    */
   public Money plus(Money m)
   {
      long val = myCents + m.myCents;
      return new Money(val);
   }

   /**
    * Add an amount of Money, represented as a double, to this value and return
    * the result.
    * 
    * @param val Amount to add.
    * @return Result in the form of a Money object.
    */
   public Money plus(double val)
   {
      return this.plus(new Money(val));
   }
   
   /**
    * Subtract an amount of Money from this value and return the result.
    * 
    * @param m Amount to subtract.
    * @return Result in the form of a Money object.
    */
   public Money minus(Money m)
   {
      long val = myCents - m.myCents;
      return new Money(val);
   }

   /**
    * Subtract an amount of Money, represented as a double, from this value and
    * return the result.
    * 
    * @param val Amount to subtract.
    * @return Result in the form of a Money object.
    */
   public Money minus(double val)
   {
      return this.minus(new Money(val));
   }

   /**
    * Subtract an amount of Money from this value and return the result, unless
    * the result is a negative value, then return ZERO. Never returns a negative
    * result.
    * 
    * @param m Amount to subtract.
    * @return Result in the form of a Money object.
    */
   public Money minusToZero(Money m)
   {
      long val = myCents - m.myCents;
      if (val <= 0)
         return Money.ZERO;
      return new Money(val);
   }

   /**
    * Divide this value by the divisor and return the result. Rounds to 
    * the nearest penny.
    * 
    * @param divisor divisor of operation.
    * @return Result in the form of a Money object.
    */
   public Money divideBy(long divisor)
   {
      return scale(1, divisor);
   }

   /**
    * Divide this value by the divisor and return the result. Result is rounded
    * to nearest penny with RoundingMode.HALF_UP mode.
    * 
    * @param divisor divisor of operation.
    * @return Result in the form of a Money object.
    */
   public Money divideBy(double divisor)
   {
      // Do operation.
      divisor *= 100;
      double res = myCents / divisor;
      if (Double.isNaN(res) || Double.isInfinite(res))
      {
         throw new IllegalArgumentException("Cannot divide by zero!");
      }
      return new Money(calcCents(res));
   }

   /**
    * Multiply this value by the multiplier and return the result.
    * 
    * @param multiplier multiplier of operation.
    * @return Result in the form of a Money object.
    */
   public Money multiplyBy(long multiplier)
   {
      long val = myCents * multiplier;
      return new Money(val);
   }

   /**
    * Multiply this value by the multiplier and return the result. Result is
    * rounded to nearest penny with RoundingMode.HALF_UP mode.
    * 
    * @param multiplier multiplier of operation.
    * @return Result in the form of a Money object.
    */
   public Money multiplyBy(double multiplier)
   {
      BigDecimal bigRes = BigDecimal.valueOf(multiplier);
      bigRes = bigRes.multiply(new BigDecimal(myCents));
      MathContext mc = new MathContext(getPrecision(bigRes.longValue()), RoundingMode.HALF_UP);
      return new Money(bigRes.round(mc).longValue());
   }

   /**
    * Scale the monetary value by a ratio of numerator to denominator.
    * This method performs integer arithmetic. Values are rounded if the remainder of the
    * division is greater than or equal to half the denominator. Rounding is always 'away' 
    * from zero:
    *     0.5 ->  1
    *    -0.5 -> -1  
    * 
    * @param numerator value.
    * @param denominator value.
    * @return Result in the form of a Money object.
    */
   public Money scale(long numerator, long denominator)
   {
      return Money.newFromPennies(scale(myCents, numerator, denominator));
   }

   /**
    * Get a percentage of this monetary value. This method is for whole number
    * percentages. The result is rounded to the nearest penny.
    * 
    * @param percentage value. For example, value of 12 would be represent 12
    *        percent.
    * @return Result in the form of a Money object.
    */
   public Money percent(int percentage)
   {
      return Money.newFromPennies(scale(myCents, percentage, 100));
   }

   /**
    * Get a percentage of this monetary value rounded to the nearest penny.
    * 
    * @param percentage value. For example, value of 12.0 would be represent 12
    *        percent.
    * @return Result in the form of a Money object.
    */
   public Money percent(float percentage)
   {
      BigDecimal bdPercent = BigDecimal.valueOf(percentage);
      BigDecimal bdCents = BigDecimal.valueOf(myCents);

      BigDecimal bdResult = bdPercent.multiply(bdCents).divide(BIG_DECIMAL_100);
      MathContext mc = new MathContext(getPrecision(bdResult.longValue()), RoundingMode.HALF_UP);
      return new Money(bdResult.round(mc).longValue());
   }
   
   /**
    * Multiply a long value by a double precision multiplier and return a long result scaleed
    * to the nearest whole number.
    * @param initalValue
    * @param multiplier
    * @return
    */
   public static long multiplyBy(long initialValue, double multiplier)
   {
      BigDecimal bigRes = BigDecimal.valueOf(multiplier);
      bigRes = bigRes.multiply(new BigDecimal(initialValue));
      MathContext mc = new MathContext(getPrecision(bigRes.longValue()), RoundingMode.HALF_UP);
      return bigRes.round(mc).longValue();
   }
   
   /**
    * Multiply a long value by a double precision multiplier and return a long result scaleed
    * to the nearest whole number.
    * @param initalValue
    * @param multiplier
    * @return
    */
   public static long DivideBy(long initialValue, long divisor)
   {
      return scale(initialValue, 1, divisor);
   }
   
   public static long percent(long initialValue, int percentage)
   {
      return scale(initialValue, percentage, 100);
   }
   
   /**
    * Scaling method performing integer arithmetic. This method takes the initial
    * value and scales by the numerator and denominator. The result is rounded to the nearest 
    * integer value. 
    * @param initalValue
    * @param numerator
    * @param denominator
    * @return initialValue*numerator/denominator rounded to nearest integer
    */
   public static long scale(long initalValue, long numerator, long denominator)
   {
      // TODO: move this to another, more appropriate utility class
      if (denominator == 0)
         throw new IllegalArgumentException("Zero divisor");
      
      // compute the dividend
      long num = initalValue * numerator;
      // Do the integer division
      long val = num / denominator;
      // Get the absolute value of the remainder of the integer division
      long mod = Math.abs(num % denominator);
      // If the remainder is greater or equal to half the divisor we'll have to round  
      // We actually double the remainder and compare to the denominator so we don't lose precision.
      if ((mod << 1) >= Math.abs(denominator))
      {
         if (Long.signum(num) == Long.signum(denominator))
            val++;         // Result is positive round up (away from zero)
         else
            val--;         // Result is negative round down (away from zero)
      }
      return val;
   }

   /**************** BOOLEAN TESTS ***********************/

   /**
    * Is this monetary value less than another monetary value?
    * 
    * @param other Monetary value to be compared.
    * @return <b>true </b> This monetary value is less than the specified
    *         monetary value. <br>
    *         <b>false</b> This monetary value is not less than the specified
    *         monetary value.
    */
   public boolean isLessThan(Money other)
   {
      return (this.myCents < other.myCents);
   }

   /**
    * Is this monetary value less than or equal to another monetary value?
    * 
    * @param other Monetary value to be compared.
    * @return <b>true </b> This monetary value is less than or equal to the
    *         specified monetary value. <br>
    *         <b>false</b> This monetary value is not less than or equal to the
    *         the specified monetary value.
    */
   public boolean isLessThanOrEqual(Money other)
   {
      return (this.myCents <= other.myCents);
   }

   /**
    * Is this monetary value greater than another monetary value?
    * 
    * @param other Monetary value to be compared.
    * @return <b>true </b> This monetary value is greater than the specified
    *         monetary value. <br>
    *         <b>false</b> This monetary value is not greater than the specified
    *         monetary value.
    */
   public boolean isGreaterThan(Money other)
   {
      return (this.myCents > other.myCents);
   }

   /**
    * Is this monetary value greater than or equal to another monetary value?
    * 
    * @param other Monetary value to be compared.
    * @return <b>true </b> This monetary value is greater than or equal to the
    *         specified monetary value. <br>
    *         <b>false</b> This monetary value is not greater than or equal to
    *         the the specified monetary value.
    */
   public boolean isGreaterThanOrEqual(Money other)
   {
      return (this.myCents >= other.myCents);
   }

   /**
    * Is this monetary value equal to another monetary value?
    * 
    * @param other Monetary value to be compared.
    * @return <b>true </b> if values are equal, false if not.
    */
   public boolean isEqual(Money other)
   {
      return (other.myCents == myCents);
   }

   /**
    * @return <b>true</b> if this value is zero, <b>false</b> otherwise.
    */
   public boolean isZero()
   {
      return (myCents == 0L);
   }

   /**
    * Is this monetary value is positive (greater than or equal to $0.00)?
    * 
    * @return <b>true </b> The monetary value is positive, <b>false</b>
    *         otherwise.
    */
   public boolean isPositive()

   {
      return (myCents >= 0L);
   }

   /**
    * Is this monetary value negative (less than $0.00)?
    * 
    * @return <b>true </b> The monetary value is negative, <b>false</b>
    *         otherwise.
    */
   public boolean isNegative()

   {
      return (myCents < 0L);
   }

   /************** GENERAL OBJECT SUPPORT *************/

   /**
    * Compares this object with the specified object. The objects are equal if
    * and only if the specified object is not null, is a Money object, and has
    * the same monetary value as this object.
    * 
    * @param other Object to compare.
    */
   @Override
   public boolean equals(Object other)

   {
      if (other == this)
      {
         // Same object
         return true;
      }

      if (other == null)
      {
         // null always false
         return false;
      }

      if (!(other instanceof Money))
      {
         // Another type of object
         return false;
      }
      return (((Money) other).myCents == myCents);
   }

   /**
    * @return Hash code for this object.
    */
   @Override
   public int hashCode()
   {
      return Long.valueOf(myCents).hashCode();
   }

   /**
    * Get the decimal value of this monetary value as a double value.
    * 
    * @return Floating point representation of this monetary value, with
    */
   public double toDouble()
   {
      BigDecimal bd = BigDecimal.valueOf(myCents);
      bd = bd.divide(BigDecimal.valueOf(CENTS_PER_DOLLAR));
      return bd.doubleValue();
   }
   
   /**
    * Get the decimal value of this monetary value as a BigDecimal.
    * 
    * @return BigDecimal representation of this monetary value
    */
   public BigDecimal toBigDecimal()
   {
      BigDecimal bd = BigDecimal.valueOf(myCents);
      bd = bd.divide(BIG_DECIMAL_100);
      return bd;
   }
   
      /**
    * Get the currency definition for this monetary value.
    * 
    * @return Currency definition.
    */
   public Currency currency()
   {
      return US_CURRENCY;
   }

   /**
    * String representation of this Money object in currency format with ',' For
    * example $23,000.00 $2,000.50
    */
   @Override
   public String toString()
   {
      return getCurrencyFormat().format(this.toDouble());
   }

   /**
    * String representation of this Money object with '$' and without ',' This
    * function is currently used by policy csv export. The policy CSV file use
    * coma as delimiter, therefore we will export the money value with a dollar
    * sign and without coma For example $23000.00 $2000.50
    */
   public String toStringCsv()
   {
      if (myCents > 0)
         return ("$" + toValueString());
      else
         return String.format("-$%s", String.valueOf(Math.abs(toDouble())));
   }

   /**
    * Format this monetary value such that it is suitable for being passed to
    * Float.valueOf(). The $ sign and any commas or parentheses are not
    * included.
    */
   public String toValueString()
   {
      return String.valueOf(toDouble());
   }

   /**
    * @param m Money object to compare this with.
    * @return a negative integer, zero, or positive integer if this object is
    *         less than, equal to, or greater than the argument.
    * @see Comparable
    */
   public int compareTo(Money m)
   {
      Long val = Long.valueOf(myCents);
      return val.compareTo(m.myCents);
   }

   /********* PRIVATE SECTION ***********/

   /**
    * Given dollars and cents, return the total number of cents, suitable for
    * use as an argument to Money(long) constructor.
    * 
    * @param dollars
    * @param cents
    * @return Total number of cents.
    */
   private static long calcCents(long dollars, int cents)
   {
      long val = (dollars * CENTS_PER_DOLLAR) + cents;
      return val;
   }

   /**
    * Given a decimal representation of a monetary value, return the total
    * number of cents it represents, rounded to the nearest penny.
    * 
    * @param dollarsCents
    * @return number of cents.
    */
   private static long calcCents(double dollarsCents)
   {
       // do all calculations on positive values.
       boolean isNeg = (dollarsCents < 0);
       // Convert the input value to tenths of a cent
       long absKVal = Double.valueOf(Math.abs(dollarsCents) * 1000).longValue();
       // calculate penny rounding. Divide by 10 and take the remainder
       int mod = (int)(absKVal % 10);
       long rtn = absKVal / 10;
       // if the remainder is greater than or equal to 5, we need to round
       if (mod >= 5)
           rtn++;
       // put sign back.
       if (isNeg)
           rtn *= -1;
       return rtn;
   }

   /**
    * Get the currency format. The only format supported is US.
    * 
    * @return NumberFormat for US currency.
    */
   private static NumberFormat getCurrencyFormat()
   {
      return NumberFormat.getCurrencyInstance(Locale.US);
   }

   /**
    * Find the precision required to correctly round a long value to its decimal
    * point.
    * 
    * @param val value
    * @return Number of decimal places in this value.
    */
   private static int getPrecision(long val)
   {
      int precision = String.valueOf(val).length();
      if (val < 0)
         precision--;
      return precision;
   }

   /******* CLASS MEMBERS ****/
   public static final int CENTS_PER_DOLLAR = 100;
   /**
    * The special monetary value of zero ($0.00).
    */
   public static final Money ZERO = new Money(0L);

   public static final BigDecimal BIG_DECIMAL_100 = BigDecimal.valueOf(100);

   /**
    * The currency definition for this class.
    */
   private static final Currency US_CURRENCY = Currency.getInstance(Locale.US);

   /** Serializable interface requirement */
   private static final long serialVersionUID = 1L;

   /******* OBJECT MEMBERS ****/
   private final long myCents; // Total number of cents in this value.
}
