package com.dealshield.comparison;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ComparisonEngine {
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    public Analysis analyze(String priority,List<Quote> quotes,java.util.Map<String,Object> requirements){
        Analysis base=analyze(priority,quotes);
        if(requirements==null||requirements.isEmpty())return base;
        List<RankedQuote> assessed=new ArrayList<>();boolean uncertain=false;
        for(RankedQuote ranked:base.ranking()){
            Quote q=quotes.get(ranked.slot()-1);List<Finding> findings=new ArrayList<>(ranked.findings());
            BigDecimal required=decimal(requirements.get("requiredQuantity"));
            if(required!=null){if(q.quantity()==null){findings.add(new Finding("QUANTITY_UNVERIFIED","MEDIUM","Offered quantity is missing.","No quantity in reviewed quotation","quantity","Confirm quantity with the supplier."));uncertain=true;}else if(q.quantity().compareTo(required)<0)findings.add(new Finding("QUANTITY_SHORTFALL","HIGH","Offered quantity is below the buyer's requirement.",q.quantity()+" offered versus "+required+" required","quantity","Request the full quantity."));}
            BigDecimal maximum=decimal(requirements.get("maximumDeliveryDays"));if(maximum!=null&&q.deliveryDays()>maximum.intValue())findings.add(new Finding("DELIVERY_REQUIREMENT","HIGH","Delivery exceeds the buyer's maximum.",q.deliveryDays()+" versus "+maximum+" days","delivery_days","Confirm an acceptable delivery date."));
            BigDecimal warranty=decimal(requirements.get("minimumWarrantyMonths"));if(warranty!=null&&warrantyMonths(q.warrantyTerms())<warranty.intValue())findings.add(new Finding("WARRANTY_REQUIREMENT","HIGH","Warranty is below the buyer's minimum or is undocumented.",safe(q.warrantyTerms()),"warranty_terms","Obtain sufficient written warranty coverage."));
            Object requestedSpecifications=requirements.get("mandatorySpecifications");String mandatory=requestedSpecifications==null?"":requestedSpecifications.toString();
            for(String line:mandatory.split("\\R")){if(line.isBlank())continue;String[] parts=line.split(":",2);String evidence=q.specifications()==null?null:q.specifications().get(parts[0].trim().toLowerCase(Locale.ROOT));if(evidence==null||parts.length<2||!evidence.toLowerCase(Locale.ROOT).contains(parts[1].trim().toLowerCase(Locale.ROOT))){findings.add(new Finding("SPECIFICATION_UNVERIFIED","MEDIUM","Mandatory specification cannot be verified from this quotation.",line+"; offered: "+safe(evidence),"specifications","Confirm the exact specification before purchase."));uncertain=true;}else findings.add(new Finding("SPECIFICATION_VERIFIED","INFORMATIONAL","Mandatory specification appears in the quotation.",line+"; offered: "+evidence,"specifications","Verify the model or SKU before ordering."));}
            assessed.add(new RankedQuote(ranked.slot(),ranked.supplierName(),ranked.total(),ranked.deliveryDays(),ranked.score(),findings));
        }
        RankedQuote eligible=assessed.stream().filter(r->r.findings().stream().noneMatch(f->List.of("QUANTITY_SHORTFALL","DELIVERY_REQUIREMENT","WARRANTY_REQUIREMENT","QUANTITY_UNVERIFIED","SPECIFICATION_UNVERIFIED").contains(f.type()))).findFirst().orElse(null);
        if(base.recommendedSupplier()==null||eligible==null)return new Analysis(0,null,"Buyer requirements need confirmation. Review the supplier-specific compliance findings before selecting an offer.",assessed);
        return new Analysis(eligible.slot(),eligible.supplierName(),"Ranked "+eligible.supplierName()+" highest among offers with documented mandatory requirements. Review unit price, quantity, specifications and all risk findings before purchase.",assessed);
    }
    private BigDecimal decimal(Object value){if(value==null||String.valueOf(value).isBlank())return null;try{return new BigDecimal(String.valueOf(value));}catch(NumberFormatException e){return null;}}

    public Analysis analyze(String priority, List<Quote> quotes) {
        if (quotes == null || quotes.size() < 2 || quotes.size() > 3) throw new IllegalArgumentException("Provide 2 or 3 quotations");
        boolean comparableCurrency=quotes.stream().map(Quote::currency).distinct().count()==1;
        boolean comparableQuantity=quotes.stream().map(Quote::quantity).distinct().count()==1;
        BigDecimal minTotal = quotes.stream().map(Quote::comparisonCost).min(Comparator.naturalOrder()).orElseThrow();
        int minDelivery = quotes.stream().mapToInt(Quote::deliveryDays).min().orElse(1);
        List<RankedQuote> ranked = new ArrayList<>();
        for (int i=0;i<quotes.size();i++) {
            Quote quote=quotes.get(i); List<Finding> findings=risks(quote,comparableCurrency?minTotal:quote.comparisonCost());
            if(!comparableCurrency)findings.add(new Finding("CURRENCY_MISMATCH","HIGH","Currencies differ; prices cannot be ranked without a verified exchange rate.",quote.currency(),"currency","Obtain comparable currency quotations."));
            if(!comparableQuantity)findings.add(new Finding("QUANTITY_MISMATCH","MEDIUM","Offered quantities differ; compare unit prices and required quantities.",String.valueOf(quote.quantity()),"quantity","Confirm the buyer's required quantity."));
            double cost=comparableCurrency?minTotal.divide(quote.comparisonCost().max(BigDecimal.ONE),6,RoundingMode.HALF_UP).doubleValue()*100:0;
            double delivery=100d*Math.max(1,minDelivery)/Math.max(1,quote.deliveryDays());
            double warranty=Math.min(36,warrantyMonths(quote.warrantyTerms()))/36d*100;
            double payment=Math.min(60,paymentDays(quote.paymentTerms()))/60d*100;
            double[] w=weights(priority); double penalty=findings.stream().mapToDouble(f->switch(f.severity()){case "HIGH"->15;case "MEDIUM"->7;case "LOW"->3;default->0;}).sum();
            double score=Math.max(0,Math.min(100,cost*w[0]+delivery*w[1]+warranty*w[2]+payment*w[3]-penalty));
            ranked.add(new RankedQuote(i+1,quote.supplierName(),quote.total(),quote.deliveryDays(),Math.round(score*10d)/10d,findings));
        }
        ranked.sort(Comparator.comparingDouble(RankedQuote::score).reversed());
        RankedQuote winner=ranked.getFirst();
        String explanation=comparableCurrency?"Ranked "+winner.supplierName()+" with score "+winner.score()+"/100 for "+priority.replace('_',' ').toLowerCase(Locale.ROOT)+" priority. Cost uses unit price when available, alongside delivery, warranty and payment terms. Review model and specification differences before purchase.":"No price recommendation: quotations use different currencies. Review products and obtain a verified exchange rate first.";
        return new Analysis(comparableCurrency?winner.slot():0,comparableCurrency?winner.supplierName():null,explanation,ranked);
    }

    private List<Finding> risks(Quote q, BigDecimal minTotal) {
        List<Finding> r=new ArrayList<>();
        if(q.comparisonCost().compareTo(minTotal.multiply(new BigDecimal("1.15")))>0) r.add(new Finding("PRICE_OUTLIER","MEDIUM","Comparable cost is more than 15% above the lowest quotation.","Unit/comparable cost: "+q.comparisonCost(),"unit_price","Confirm that the premium provides measurable value."));
        if(q.deliveryDays()>30) r.add(new Finding("SLOW_DELIVERY","HIGH","Delivery exceeds 30 days.",q.deliveryDays()+" days","delivery_days","Confirm the schedule and add a late-delivery remedy."));
        else if(q.deliveryDays()>14) r.add(new Finding("DELIVERY_DELAY","MEDIUM","Delivery exceeds 14 days.",q.deliveryDays()+" days","delivery_days","Check whether the delivery date meets the purchase need."));
        if(warrantyMonths(q.warrantyTerms())==0) r.add(new Finding("WARRANTY_MISSING","HIGH","No measurable warranty period was provided.",safe(q.warrantyTerms()),"warranty_terms","Request written warranty duration and coverage."));
        else if(warrantyMonths(q.warrantyTerms())<12) r.add(new Finding("SHORT_WARRANTY","MEDIUM","Warranty is shorter than 12 months.",q.warrantyTerms(),"warranty_terms","Compare expected product life with the warranty period."));
        String payment=safe(q.paymentTerms()).toLowerCase(Locale.ROOT);
        if(payment.isBlank() || payment.contains("advance") || payment.contains("prepaid") || payment.contains("100%")) r.add(new Finding("PAYMENT_EXPOSURE","HIGH","Payment terms require advance payment or are not clear.",safe(q.paymentTerms()),"payment_terms","Negotiate staged payment or payment after delivery."));
        if(q.subtotal().signum()>0) { BigDecimal rate=q.tax().divide(q.subtotal(),4,RoundingMode.HALF_UP); if(rate.compareTo(new BigDecimal("0.28"))>0) r.add(new Finding("HIGH_TAX_RATE","MEDIUM","Tax exceeds 28% of subtotal.","Calculated rate: "+rate.multiply(BigDecimal.valueOf(100)).setScale(1,RoundingMode.HALF_UP)+"%","tax_amount","Verify the tax classification and calculation.")); }
        if(r.isEmpty()) r.add(new Finding("NO_RULE_RISKS","INFORMATIONAL","No material issue was detected by the current rules.","All required commercial fields passed the configured checks.",null,"Complete normal commercial and legal review before purchase."));
        return r;
    }

    private double[] weights(String priority) { return switch(priority){case "LOWEST_COST"->new double[]{.60,.15,.10,.15};case "FASTEST_DELIVERY"->new double[]{.25,.55,.10,.10};case "BEST_WARRANTY"->new double[]{.25,.15,.50,.10};default->new double[]{.40,.25,.20,.15};}; }
    private int warrantyMonths(String s){String x=safe(s).toLowerCase(Locale.ROOT);Matcher m=NUMBER.matcher(x);if(!m.find())return 0;int n=Integer.parseInt(m.group(1));return x.contains("year")?n*12:n;}
    private int paymentDays(String s){String x=safe(s).toLowerCase(Locale.ROOT);if(x.contains("advance")||x.contains("prepaid")||x.contains("100%"))return 0;if(x.contains("delivery"))return 15;Matcher m=NUMBER.matcher(x);return m.find()?Integer.parseInt(m.group(1)):0;}
    private String safe(String s){return s==null?"":s.trim();}

    public record Quote(String supplierName,BigDecimal subtotal,BigDecimal tax,BigDecimal shipping,BigDecimal additionalCharges,int deliveryDays,String warrantyTerms,String paymentTerms,String currency,BigDecimal quantity,BigDecimal unitPrice,java.util.Map<String,String> specifications){public Quote(String supplierName,BigDecimal subtotal,BigDecimal tax,BigDecimal shipping,BigDecimal additionalCharges,int deliveryDays,String warrantyTerms,String paymentTerms){this(supplierName,subtotal,tax,shipping,additionalCharges,deliveryDays,warrantyTerms,paymentTerms,"INR",null,null,java.util.Map.of());}public Quote(String supplierName,BigDecimal subtotal,BigDecimal tax,BigDecimal shipping,BigDecimal additionalCharges,int deliveryDays,String warrantyTerms,String paymentTerms,String currency,BigDecimal quantity,BigDecimal unitPrice){this(supplierName,subtotal,tax,shipping,additionalCharges,deliveryDays,warrantyTerms,paymentTerms,currency,quantity,unitPrice,java.util.Map.of());}public BigDecimal total(){return subtotal.add(tax).add(shipping).add(additionalCharges);}public BigDecimal comparisonCost(){return unitPrice!=null&&unitPrice.signum()>0?unitPrice:quantity!=null&&quantity.signum()>0?total().divide(quantity,4,RoundingMode.HALF_UP):total();}}
    public record Finding(String type,String severity,String description,String evidence,String sourceField,String recommendation){}
    public record RankedQuote(int slot,String supplierName,BigDecimal total,int deliveryDays,double score,List<Finding> findings){}
    public record Analysis(int recommendedSlot,String recommendedSupplier,String explanation,List<RankedQuote> ranking){}
}
