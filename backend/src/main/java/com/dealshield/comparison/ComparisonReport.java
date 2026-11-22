package com.dealshield.comparison;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

@Service
public class ComparisonReport {
 public record Item(String description,String quantity,String unitPrice,String lineTotal){}
 public record Offer(String supplier,String currency,String reference,String product,String model,String quantity,String unitPrice,String subtotal,String tax,String shipping,String charges,String total,String delivery,String warranty,String payment,List<Item> items,Map<String,String> specifications){}
 public record Input(UUID id,String title,String category,String priority,String recommendation,String explanation,List<Offer> offers,List<String> risks){}

 public byte[] render(Input data) {
  try(PDDocument doc=new PDDocument()){
   List<String> lines=new ArrayList<>();
   lines.add("DEALSHIELD AI  |  BUYER ANALYSIS REPORT");lines.add("Report ID: "+data.id());lines.add("Generated: "+LocalDate.now());lines.add("");
   lines.add("EXECUTIVE SUMMARY");lines.add("Purchase: "+safe(data.title()));lines.add("Category: "+safe(data.category()));lines.add("Priority: "+safe(data.priority()));
   lines.add("Recommendation: "+safe(data.recommendation()));lines.add(safe(data.explanation()));lines.add("");
   lines.add("SUPPLIER COMPARISON");
   boolean sameCurrency=data.offers().stream().map(Offer::currency).distinct().count()==1;
   boolean sameQuantity=data.offers().stream().map(Offer::quantity).distinct().count()==1;
   if(!sameCurrency||!sameQuantity)lines.add("Caution: quantities or currencies differ. Grand totals are not directly comparable.");
   for(Offer o:data.offers()){
    lines.add("");lines.add("SUPPLIER: "+safe(o.supplier()));
    lines.add("Reference: "+safe(o.reference())+" | Product: "+safe(o.product())+" | Model: "+safe(o.model()));
    lines.add("Quantity: "+safe(o.quantity())+" | Unit price: "+safe(o.currency())+" "+safe(o.unitPrice()));
    lines.add("Subtotal: "+safe(o.subtotal())+" | Tax: "+safe(o.tax())+" | Shipping: "+safe(o.shipping()));
    lines.add("Other charges: "+safe(o.charges())+" | Total: "+safe(o.currency())+" "+safe(o.total()));
    lines.add("Delivery: "+safe(o.delivery())+" | Warranty: "+safe(o.warranty()));lines.add("Payment: "+safe(o.payment()));
    for(var spec:o.specifications().entrySet())lines.add(spec.getKey()+": "+safe(spec.getValue()));
    for(Item item:o.items())lines.add("Item: "+safe(item.description())+" | Qty: "+safe(item.quantity())+" | Unit: "+safe(item.unitPrice())+" | Line: "+safe(item.lineTotal()));
   }
   lines.add("");lines.add("RECORDED RISK FINDINGS");lines.addAll(data.risks());
   lines.add("");lines.add("Decision support only. The buyer retains final purchasing authority.");
   PDType1Font regular=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
   PDType1Font bold=new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
   int pageNumber=0;PDPage page=null;PDPageContentStream stream=null;float y=0;
   for(String line:lines){for(String part:wrap(ascii(line),95)){
    if(stream==null||y<56){if(stream!=null)stream.close();page=new PDPage(PDRectangle.A4);doc.addPage(page);pageNumber++;stream=new PDPageContentStream(doc,page);y=PDRectangle.A4.getHeight()-52;}
    boolean heading=part.equals(part.toUpperCase())&&part.length()<55&&!part.isBlank();stream.beginText();stream.setFont(heading?bold:regular,heading?11:9);stream.newLineAtOffset(48,y);stream.showText(part);stream.endText();y-=heading?19:14;
   }}if(stream!=null)stream.close();
   for(int i=0;i<doc.getNumberOfPages();i++){try(PDPageContentStream footer=new PDPageContentStream(doc,doc.getPage(i),PDPageContentStream.AppendMode.APPEND,true)){footer.beginText();footer.setFont(regular,8);footer.newLineAtOffset(48,30);footer.showText("DealShield AI  |  Page "+(i+1)+" of "+doc.getNumberOfPages());footer.endText();}}
   ByteArrayOutputStream output=new ByteArrayOutputStream();doc.save(output);return output.toByteArray();
  }catch(IOException e){throw new IllegalStateException("Could not generate report",e);}
 }
 private String safe(Object value){return value==null||value.toString().isBlank()?"Not provided":value.toString();}
 private String ascii(String s){return s.replaceAll("[^\\x20-\\x7E]","?");}
 private List<String> wrap(String value,int width){List<String> out=new ArrayList<>();while(value.length()>width){int end=value.lastIndexOf(' ',width);if(end<1)end=width;out.add(value.substring(0,end));value=value.substring(end).trim();}out.add(value);return out;}
}
