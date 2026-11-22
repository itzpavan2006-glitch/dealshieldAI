package com.dealshield.comparison;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ComparisonReportTest {
 @Test void rendersTwoAndThreeSupplierReportsWithMissingFieldsAndMultiplePages() throws Exception {
  for(int count:List.of(2,3)){
   List<ComparisonReport.Offer> offers=new ArrayList<>();
   for(int i=0;i<count;i++){List<ComparisonReport.Item> items=new ArrayList<>();for(int j=0;j<45;j++)items.add(new ComparisonReport.Item("Laptop configuration "+j,"10","45000","450000"));offers.add(new ComparisonReport.Offer("Supplier "+i,"INR",null,null,null,"10","45000","450000","81000","5000","0","536000","5 days","24 months","Net 30",items,Map.of("RAM","16 GB")));}
   byte[] pdf=new ComparisonReport().render(new ComparisonReport.Input(UUID.randomUUID(),"College laptops","IT","BALANCED","Supplier 0","Verified saved ranking",offers,List.of("MEDIUM | Delivery | Evidence: 5 days")));
   assertTrue(pdf.length>1000);try(var document=Loader.loadPDF(pdf)){assertTrue(document.getNumberOfPages()>1);String text=new PDFTextStripper().getText(document);assertTrue(text.contains("Supplier "+(count-1)));assertTrue(text.contains("450000"));assertTrue(text.contains("Not provided"));assertTrue(text.contains("buyer retains final purchasing authority"));}
  }
 }
}
