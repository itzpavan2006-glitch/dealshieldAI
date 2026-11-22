package com.dealshield.comparison;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComparisonEngineTest {
 @Test void recommendsBalancedValueAndExplainsRisks(){
  var engine=new ComparisonEngine();
  var result=engine.analyze("BALANCED",List.of(
   q("Reliable",1000,180,20,0,7,"24 months","Net 30 days"),
   q("Risky",850,153,0,0,45,"No warranty","100% advance")));
  assertEquals("Reliable",result.recommendedSupplier());
  assertTrue(result.explanation().contains("unit price"));
  assertTrue(result.ranking().stream().filter(x->x.supplierName().equals("Risky")).flatMap(x->x.findings().stream()).anyMatch(x->x.type().equals("SLOW_DELIVERY")));
  assertTrue(result.ranking().stream().allMatch(x->x.score()>=0&&x.score()<=100));
 }
 @Test void requiresTwoOrThreeQuotes(){assertThrows(IllegalArgumentException.class,()->new ComparisonEngine().analyze("BALANCED",List.of(q("A",1,0,0,0,1,"12 months","Net 30 days"))));}
 @Test void quantityMismatchUsesUnitPriceAndFlagsNonComparableTotals(){var result=new ComparisonEngine().analyze("BALANCED",List.of(new ComparisonEngine.Quote("Ten units",new BigDecimal("450000"),new BigDecimal("81000"),new BigDecimal("5000"),BigDecimal.ZERO,5,"24 months","Net 30","INR",BigDecimal.TEN,new BigDecimal("45000")),new ComparisonEngine.Quote("Twenty units",new BigDecimal("1150000"),new BigDecimal("207000"),BigDecimal.ZERO,BigDecimal.ZERO,10,"12 months","50% advance","INR",new BigDecimal("20"),new BigDecimal("57500"))));assertTrue(result.ranking().stream().allMatch(q->q.findings().stream().anyMatch(f->f.type().equals("QUANTITY_MISMATCH"))));assertTrue(result.explanation().contains("unit price"));}
 @Test void currencyMismatchDoesNotDeclareWinner(){var result=new ComparisonEngine().analyze("BALANCED",List.of(new ComparisonEngine.Quote("A",new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,5,"12 months","Net 30","USD",BigDecimal.ONE,new BigDecimal("100")),new ComparisonEngine.Quote("B",new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,5,"12 months","Net 30","INR",BigDecimal.ONE,new BigDecimal("100"))));assertNull(result.recommendedSupplier());assertTrue(result.ranking().stream().allMatch(q->q.findings().stream().anyMatch(f->f.type().equals("CURRENCY_MISMATCH"))));}
 @Test void mandatorySpecificationRequiresEvidence(){var a=new ComparisonEngine.Quote("Documented",new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,5,"24 months","Net 30","INR",BigDecimal.ONE,new BigDecimal("100"),java.util.Map.of("ram","16 GB DDR4"));var b=new ComparisonEngine.Quote("Unknown",new BigDecimal("90"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,5,"24 months","Net 30","INR",BigDecimal.ONE,new BigDecimal("90"),java.util.Map.of());var result=new ComparisonEngine().analyze("BALANCED",List.of(a,b),java.util.Map.of("mandatorySpecifications","RAM: 16 GB"));assertEquals("Documented",result.recommendedSupplier());assertTrue(result.ranking().stream().filter(q->q.supplierName().equals("Unknown")).flatMap(q->q.findings().stream()).anyMatch(f->f.type().equals("SPECIFICATION_UNVERIFIED")));}
 private ComparisonEngine.Quote q(String n,int s,int t,int sh,int a,int d,String w,String p){return new ComparisonEngine.Quote(n,BigDecimal.valueOf(s),BigDecimal.valueOf(t),BigDecimal.valueOf(sh),BigDecimal.valueOf(a),d,w,p);}
}
