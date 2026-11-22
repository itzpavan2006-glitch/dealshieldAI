package com.dealshield.upload;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

@Service
public class QuotationExtractor {
 private static final String MONEY="(?:INR|Rs\\.?|₹|USD|EUR|GBP|\\$)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,4})?)";
 public Draft extract(byte[] bytes,String mime){
  List<String>warnings=new ArrayList<>();String text="";
  if("application/pdf".equals(mime)){try(var pdf=Loader.loadPDF(bytes)){text=new PDFTextStripper().getText(pdf);}catch(IOException e){throw new ExtractionFailure("PDF could not be read",e);}if(text.isBlank())warnings.add("This PDF contains no extractable text. Enter the fields manually; scanned-PDF OCR is not installed.");}
  else warnings.add("Image OCR is not installed. The file is stored privately; enter the fields manually.");
  return parse(text,warnings);
 }
 Draft parse(String text,List<String>warnings){
  warnings=new ArrayList<>(warnings);
  String[] lines=text.replace('\uFFFD',' ').split("\\R");
  String supplier=label(lines,"supplier name","supplier","vendor name","vendor","from");
  if(supplier!=null)supplier=supplier.replaceFirst("(?i)\\s+valid until\\b.*$","").trim();
  if(supplier!=null&&supplier.matches("(?i)^(?:quotation|quote|details|information)\\b.*"))supplier=null;
  if(supplier==null){for(String line:lines){String candidate=line.trim();if(candidate.matches("(?i)[A-Z][A-Z &.]{3,100}")&&!candidate.matches("(?i).*(QUOTATION|DEMO|SAMPLE|PRODUCT|SPECIFICATION|SUMMARY|PRICING|DETAILS|TERMS).*")){supplier=candidate;break;}}}
  String subtotalText=label(lines,"subtotal","sub total","taxable amount","taxable value");
  BigDecimal subtotal=number(subtotalText);
  String taxText=label(lines,"tax (gst","gst","igst","total tax","tax amount","tax");
  BigDecimal rate=percent(taxText);BigDecimal tax=amountAfterPercent(taxText);
  if(tax==null&&taxText!=null&&rate==null)tax=number(taxText);
  if(tax==null&&subtotal!=null&&rate!=null)tax=subtotal.multiply(rate).divide(BigDecimal.valueOf(100));
  BigDecimal shipping=number(label(lines,"shipping / delivery","shipping","freight","delivery charge"));
  String payment=label(lines,"payment terms","payment");
  if(payment==null){for(String line:lines)if(line.matches("(?i).*payment terms\\s*[:].*")){payment=line.replaceFirst("(?i)^.*payment terms\\s*[: ]+","").trim();break;}}
  if(payment==null){Matcher m=Pattern.compile("(?im)^\\s*\\d+\\.\\s*Payment terms\\s*:\\s*(.+)$").matcher(text);if(m.find())payment=m.group(1).trim();}
  String quantityText=label(lines,"quantity");BigDecimal quantity=number(quantityText);
  if(quantity==null){Matcher item=Pattern.compile("(?im)^\\s*Product\\s+([0-9]+)\\s+.*").matcher(text);if(item.find())quantity=new BigDecimal(item.group(1));}
  String unitText=label(lines,"unit price","rate per unit");BigDecimal unitPrice=number(unitText);
  String model=label(lines,"product model","model");
  String product=label(lines,"product name","product");
  String[] specKeys={"processor","memory","ram","storage","display","operating system","connectivity","keyboard"};
  java.util.Map<String,String> specs=new java.util.LinkedHashMap<>();for(String key:specKeys){String value=label(lines,key);if(value!=null)specs.put(key,value);}
  if(product!=null&&product.matches("(?i)^\\d+\\s+.*"))product=product.replaceFirst("^\\d+\\s+","");
  String quoteNo=label(lines,"quotation number","quotation no.","quotation no","quote number","quote no");
  if(quoteNo!=null)quoteNo=quoteNo.replaceFirst("(?i)\\s+quotation date\\b.*$","").trim();
  if(quoteNo==null){Matcher m=Pattern.compile("(?im)^\\s*Quotation No\\.\\s*\\R\\s*(\\S+)").matcher(text);if(m.find())quoteNo=m.group(1);}
  String date=label(lines,"quotation date","date");
  if(date==null){Matcher m=Pattern.compile("(?im)\\bQuotation Date\\s+([^\\r\\n]+)").matcher(text);if(m.find())date=m.group(1).trim();}
  if(date==null){Matcher m=Pattern.compile("(?im)^\\s*Date\\s*\\R\\s*([^\\r\\n]+)").matcher(text);if(m.find())date=m.group(1).trim();}
  String validity=label(lines,"valid until","validity");
  if(validity==null){Matcher m=Pattern.compile("(?im)\\bValid Until\\s+([^\\r\\n]+)").matcher(text);if(m.find())validity=m.group(1).trim();}
  if(validity==null){Matcher m=Pattern.compile("(?im)prices are valid for ([^.\\r\\n]+)").matcher(text);if(m.find())validity=m.group(1).trim();}
  BigDecimal total=number(label(lines,"grand total","total amount","amount payable"));
  BigDecimal charges=number(label(lines,"other charges","additional charges"));
  if(total!=null&&subtotal!=null&&tax!=null){BigDecimal balance=total.subtract(subtotal).subtract(tax);if(shipping==null&&charges==null&&balance.signum()==0){shipping=BigDecimal.ZERO;charges=BigDecimal.ZERO;}else if(shipping!=null&&charges==null&&balance.compareTo(shipping)==0)charges=BigDecimal.ZERO;else if(shipping==null&&charges!=null&&balance.compareTo(charges)==0)shipping=BigDecimal.ZERO;}
  if(quantity!=null&&unitPrice!=null&&subtotal!=null&&quantity.multiply(unitPrice).compareTo(subtotal)!=0)warnings.add("Quantity × unit price does not match the quoted subtotal; review discounts or multiple items.");
  if(total!=null&&subtotal!=null&&tax!=null&&shipping!=null&&charges!=null&&subtotal.add(tax).add(shipping).add(charges).compareTo(total)!=0)warnings.add("Quoted total does not match subtotal, tax, shipping and other charges.");
  if(rate!=null&&subtotal!=null&&tax!=null&&subtotal.multiply(rate).divide(BigDecimal.valueOf(100)).compareTo(tax)!=0)warnings.add("GST rate and amount do not match the subtotal; review tax basis.");
  String delivery=label(lines,"delivery time","delivery timeline","delivery");
  String warranty=label(lines,"warranty");
  if(supplier==null)warnings.add("Supplier name needs review.");if(subtotal==null)warnings.add("Subtotal needs review.");
  return new Draft(text,supplier,subtotal,tax,shipping,delivery,warranty,payment,List.copyOf(warnings),quoteNo,date,validity,product,model,label(lines,"product category","category"),quantity,unitPrice,rate,charges,total,java.util.Map.copyOf(specs));
 }
 private String label(String[] lines,String... keys){for(String line:lines){String trimmed=line.trim();for(String key:keys){if(trimmed.regionMatches(true,0,key,0,key.length())&&trimmed.length()>key.length()&&!Character.isLetterOrDigit(trimmed.charAt(key.length()))){String value=trimmed.substring(key.length()).replaceFirst("^[\\s:–—-]+","").trim();value=value.replaceFirst("(?i)\\s{2,}(?=(?:valid until|quotation date|quotation number|currency|quantity|date)\\b).*$","").trim();if(!value.isEmpty())return value;}}}return null;}
 private BigDecimal number(String value){if(value==null)return null;Matcher m=Pattern.compile("[0-9][0-9,]*(?:\\.[0-9]+)?").matcher(value);return m.find()?new BigDecimal(m.group().replace(",","")):null;}
 private BigDecimal percent(String value){if(value==null)return null;Matcher m=Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*%").matcher(value);return m.find()?new BigDecimal(m.group(1)):null;}
 private BigDecimal amountAfterPercent(String value){if(value==null)return null;Matcher m=Pattern.compile("%[^0-9]*([0-9][0-9,]*(?:\\.[0-9]+)?)").matcher(value);return m.find()?new BigDecimal(m.group(1).replace(",","")):null;}
 private String first(String text,String regex){Matcher m=Pattern.compile(regex).matcher(text);return m.find()?m.group(1).trim():null;}
 private BigDecimal money(String text,String regex){String value=first(text,regex);if(value==null)return null;try{return new BigDecimal(value.replace(",",""));}catch(NumberFormatException e){return null;}}
 public record Draft(String extractedText,String supplierName,BigDecimal subtotal,BigDecimal tax,BigDecimal shipping,String deliveryTerms,String warrantyTerms,String paymentTerms,List<String>warnings,String quotationNumber,String quotationDate,String validityTerms,String productName,String model,String category,BigDecimal quantity,BigDecimal unitPrice,BigDecimal taxRate,BigDecimal otherCharges,BigDecimal grandTotal,java.util.Map<String,String> specifications){}
 public static final class ExtractionFailure extends RuntimeException{ExtractionFailure(String m,Throwable c){super(m,c);}}
}
