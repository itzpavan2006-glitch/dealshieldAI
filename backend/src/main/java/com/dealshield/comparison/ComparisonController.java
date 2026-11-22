package com.dealshield.comparison;

import com.dealshield.security.BuyerTransaction;
import com.dealshield.security.VerifiedBuyer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("database")
@RequestMapping("/api/v1/comparisons")
public class ComparisonController {
    private final BuyerTransaction transactions; private final JdbcTemplate jdbc; private final ComparisonEngine engine; private final ObjectMapper json; private final ComparisonReport report;
    public ComparisonController(BuyerTransaction transactions,JdbcTemplate jdbc,ComparisonEngine engine,ObjectMapper json,ComparisonReport report){this.transactions=transactions;this.jdbc=jdbc;this.engine=engine;this.json=json;this.report=report;}

    @GetMapping public List<Summary> history(){return transactions.execute(()->jdbc.query("""
        select c.id,c.title,c.purchasing_priority,c.status,c.created_at,count(q.id) quotation_count
        from dealshield.comparisons c left join dealshield.quotations q on q.comparison_id=c.id and q.buyer_id=c.buyer_id
        group by c.id order by c.created_at desc
        """,(rs,n)->new Summary(rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("purchasing_priority"),rs.getString("status"),rs.getInt("quotation_count"),rs.getObject("created_at",OffsetDateTime.class))));}

    @GetMapping("/{id}") public Detail detail(@PathVariable UUID id){return transactions.execute(()->load(id));}

    @GetMapping(value="/{id}/report",produces="application/pdf")
    public org.springframework.http.ResponseEntity<byte[]> downloadReport(@PathVariable UUID id){
        byte[] pdf=transactions.execute(()->{
            Detail detail=load(id);
            List<ComparisonReport.Offer> offers=new java.util.ArrayList<>();
            for(QuoteView q:detail.quotations()){
                List<ComparisonReport.Item> items=jdbc.query("select description,quantity,unit_price,line_total from dealshield.quotation_items where quotation_id=? order by id",(rs,n)->new ComparisonReport.Item(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),q.id());
                Map<String,Object> extracted=jdbc.query("select extracted_json from dealshield.extraction_results where quotation_id=? order by created_at desc limit 1",(rs,n)->readMap(rs.getString(1)),q.id()).stream().findFirst().orElse(Map.of());
                Object reviewed=extracted.get("reviewedFields");Map<?,?> fields=reviewed instanceof Map<?,?> m?m:Map.of();
                Object specs=fields.get("specifications");Map<String,String> specificationMap=new java.util.LinkedHashMap<>();if(specs instanceof Map<?,?> m)m.forEach((k,v)->specificationMap.put(String.valueOf(k),String.valueOf(v)));
                offers.add(new ComparisonReport.Offer(q.supplierName(),q.currency(),string(fields.get("quotationNumber")),string(fields.get("productName")),string(fields.get("model")),string(fields.get("quantity")),string(fields.get("unitPrice")),String.valueOf(q.subtotal()),String.valueOf(q.tax()),String.valueOf(q.shipping()),String.valueOf(q.additionalCharges()),String.valueOf(q.total()),q.deliveryDays()+" days",q.warrantyTerms(),q.paymentTerms(),items,specificationMap));
            }
            List<String> findings=detail.risks().stream().map(r->r.severity()+" | "+r.type()+" | "+r.description()+" | Evidence: "+r.evidence()).toList();
            return report.render(new ComparisonReport.Input(id,detail.comparison().title(),detail.comparison().category(),detail.comparison().priority(),string(detail.result().get("recommendedSupplier")),string(detail.result().get("explanation")),offers,findings));
        });
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=DealShield_Comparison_Report_"+id+".pdf").header("Cache-Control","no-store").body(pdf);
    }
    private String string(Object value){return value==null?null:value.toString();}

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Detail create(@RequestBody CreateRequest input, Authentication authentication){
        VerifiedBuyer buyer=buyer(authentication); validate(input);
        return transactions.execute(()->{
            UUID comparison=jdbc.queryForObject("insert into dealshield.comparisons(buyer_id,title,description,category,purchasing_priority,status) values (?,?,?,?,?,'IN_REVIEW') returning id",UUID.class,buyer.authUserId(),input.title().trim(),trim(input.description()),trim(input.category()),input.priority());
            List<ComparisonEngine.Quote> quotes=input.quotations().stream().map(QuoteInput::engineQuote).toList();
            ComparisonEngine.Analysis analysis=engine.analyze(input.priority(),quotes,input.requirements());
            for(int i=0;i<input.quotations().size();i++) { int slot=i+1; saveQuote(buyer.authUserId(),comparison,slot,input.quotations().get(i),analysis.ranking().stream().filter(x->x.slot()==slot).findFirst().orElseThrow()); }
            Map<String,Object> stored=json.convertValue(analysis,new com.fasterxml.jackson.core.type.TypeReference<>(){});stored.put("buyerRequirements",input.requirements()==null?Map.of():input.requirements());
            jdbc.update("insert into dealshield.comparison_results(buyer_id,comparison_id,ranking_json,priority_snapshot,calculation_version) values (?,?,cast(? as jsonb),?,'rules-v2')",buyer.authUserId(),comparison,toJson(stored),input.priority());
            jdbc.update("update dealshield.comparisons set status='ANALYZED',updated_at=now() where id=?",comparison);
            jdbc.update("insert into dealshield.audit_events(buyer_id,action,entity_type,entity_id) values (?,'COMPARISON_ANALYZED','comparison',?)",buyer.authUserId(),comparison);
            return load(comparison);
        });
    }

    private void saveQuote(UUID buyer,UUID comparison,int slot,QuoteInput q,ComparisonEngine.RankedQuote ranked){
        UUID supplier=jdbc.queryForObject("insert into dealshield.suppliers(buyer_id,supplier_name) values (?,?) returning id",UUID.class,buyer,q.supplierName().trim());
        BigDecimal total=q.engineQuote().total();
        UUID quotation=jdbc.queryForObject("""
            insert into dealshield.quotations(buyer_id,comparison_id,supplier_id,slot,currency,subtotal,tax_amount,shipping_amount,additional_charges,grand_total,delivery_days,warranty_terms,payment_terms,extraction_status,review_status)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,'COMPLETED','CONFIRMED') returning id
            """,UUID.class,buyer,comparison,supplier,slot,q.currency().trim().toUpperCase(),q.subtotal(),q.tax(),q.shipping(),q.additionalCharges(),total,q.deliveryDays(),q.warrantyTerms().trim(),q.paymentTerms().trim());
        if(q.quantity()!=null&&q.quantity().signum()>0&&q.productName()!=null&&!q.productName().isBlank())jdbc.update("insert into dealshield.quotation_items(buyer_id,quotation_id,description,quantity,unit_price,line_total) values (?,?,?,?,?,?)",buyer,quotation,q.productName()+(q.model()==null?"":" / "+q.model()),q.quantity(),q.unitPrice(),q.subtotal());
        for(ComparisonEngine.Finding f:ranked.findings()) jdbc.update("insert into dealshield.risk_findings(buyer_id,quotation_id,finding_type,severity,description,evidence,source_field,recommendation) values (?,?,?,?,?,?,?,?)",buyer,quotation,f.type(),f.severity(),f.description(),f.evidence(),f.sourceField(),f.recommendation());
        if(q.uploadedFile()!=null){UploadedFile f=q.uploadedFile();if(!f.storagePath().startsWith(buyer+"/"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid upload ownership");jdbc.update("insert into dealshield.quotation_files(buyer_id,quotation_id,storage_path,original_filename,mime_type,file_size,checksum) values (?,?,?,?,?,?,?)",buyer,quotation,f.storagePath(),f.originalFilename(),f.mimeType(),f.fileSize(),f.checksum());Map<String,Object> reviewed=new java.util.LinkedHashMap<>();reviewed.put("quotationNumber",q.quotationNumber());reviewed.put("productName",q.productName());reviewed.put("model",q.model());reviewed.put("quantity",q.quantity());reviewed.put("unitPrice",q.unitPrice());reviewed.put("specifications",q.specifications()==null?Map.of():q.specifications());jdbc.update("insert into dealshield.extraction_results(buyer_id,quotation_id,extracted_json,confidence_metadata,extraction_method,model_version,processing_status) values (?,?,cast(? as jsonb),'{}'::jsonb,'PDFBOX_OR_MANUAL_REVIEW','rules-v2','COMPLETED')",buyer,quotation,toJson(Map.of("rawText",f.extractedText()==null?"":f.extractedText(),"reviewed",true,"reviewedFields",reviewed)));}
    }

    private Detail load(UUID id){
        Header h=jdbc.query("select id,title,description,category,purchasing_priority,status,created_at from dealshield.comparisons where id=?",(rs,n)->header(rs),id).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<QuoteView> quotes=jdbc.query("""
            select q.id,q.slot,s.supplier_name,q.currency,q.subtotal,q.tax_amount,q.shipping_amount,q.additional_charges,q.grand_total,q.delivery_days,q.warranty_terms,q.payment_terms,
              item.description product_description,item.quantity product_quantity,item.unit_price product_unit_price
            from dealshield.quotations q join dealshield.suppliers s on s.id=q.supplier_id and s.buyer_id=q.buyer_id
            left join lateral (select description,quantity,unit_price from dealshield.quotation_items where quotation_id=q.id and buyer_id=q.buyer_id order by id limit 1) item on true
            where q.comparison_id=? order by q.slot
            """,(rs,n)->quote(rs),id);
        List<RiskView> risks=jdbc.query("""
            select q.slot,r.finding_type,r.severity,r.description,r.evidence,r.recommendation from dealshield.risk_findings r join dealshield.quotations q on q.id=r.quotation_id and q.buyer_id=r.buyer_id where q.comparison_id=? order by q.slot,r.severity desc
            """,(rs,n)->new RiskView(rs.getInt("slot"),rs.getString("finding_type"),rs.getString("severity"),rs.getString("description"),rs.getString("evidence"),rs.getString("recommendation")),id);
        Map<String,Object> result=jdbc.query("select ranking_json from dealshield.comparison_results where comparison_id=? order by created_at desc limit 1",(rs,n)->readMap(rs.getString(1)),id).stream().findFirst().orElse(Map.of());
        return new Detail(h,quotes,risks,result);
    }
    private Header header(ResultSet r)throws SQLException{return new Header(r.getObject("id",UUID.class),r.getString("title"),r.getString("description"),r.getString("category"),r.getString("purchasing_priority"),r.getString("status"),r.getObject("created_at",OffsetDateTime.class));}
    private QuoteView quote(ResultSet r)throws SQLException{return new QuoteView(r.getObject("id",UUID.class),r.getInt("slot"),r.getString("supplier_name"),r.getString("currency"),r.getBigDecimal("subtotal"),r.getBigDecimal("tax_amount"),r.getBigDecimal("shipping_amount"),r.getBigDecimal("additional_charges"),r.getBigDecimal("grand_total"),r.getInt("delivery_days"),r.getString("warranty_terms"),r.getString("payment_terms"),r.getString("product_description"),r.getBigDecimal("product_quantity"),r.getBigDecimal("product_unit_price"));}
    private Map<String,Object> readMap(String value){try{return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private String toJson(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private VerifiedBuyer buyer(Authentication a){if(a==null||!(a.getPrincipal() instanceof VerifiedBuyer b))throw new org.springframework.security.access.AccessDeniedException("Verified buyer required");return b;}
    private void validate(CreateRequest r){if(r==null||r.title()==null||r.title().trim().isEmpty()||r.title().trim().length()>200||!List.of("LOWEST_COST","FASTEST_DELIVERY","BEST_WARRANTY","BALANCED").contains(r.priority())||r.quotations()==null||r.quotations().size()<2||r.quotations().size()>3)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid comparison");for(QuoteInput q:r.quotations())if(!q.valid())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid quotation");}
    private String trim(String s){return s==null||s.isBlank()?null:s.trim();}

    public record CreateRequest(String title,String description,String category,String priority,List<QuoteInput> quotations,Map<String,Object> requirements){}
    public record QuoteInput(String supplierName,String currency,BigDecimal subtotal,BigDecimal tax,BigDecimal shipping,BigDecimal additionalCharges,int deliveryDays,String warrantyTerms,String paymentTerms,UploadedFile uploadedFile,String quotationNumber,String productName,String model,BigDecimal quantity,BigDecimal unitPrice,Map<String,String> specifications){
        boolean valid(){return supplierName!=null&&!supplierName.isBlank()&&supplierName.length()<=200&&currency!=null&&currency.trim().matches("[A-Za-z]{3}")&&nonnegative(subtotal)&&nonnegative(tax)&&nonnegative(shipping)&&nonnegative(additionalCharges)&&deliveryDays>=0&&warrantyTerms!=null&&paymentTerms!=null&&validFile(uploadedFile);}
        private boolean nonnegative(BigDecimal n){return n!=null&&n.signum()>=0;}
        private boolean validFile(UploadedFile f){if(f==null)return true;return f.storagePath()!=null&&f.storagePath().matches("[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}/[^/]{1,120}")&&f.originalFilename()!=null&&f.originalFilename().length()<=120&&List.of("application/pdf","image/jpeg","image/png").contains(f.mimeType())&&f.fileSize()>0&&f.fileSize()<=10*1024*1024&&f.checksum()!=null&&f.checksum().matches("[0-9a-f]{64}");}
        ComparisonEngine.Quote engineQuote(){return new ComparisonEngine.Quote(supplierName.trim(),subtotal,tax,shipping,additionalCharges,deliveryDays,warrantyTerms,paymentTerms,currency,quantity,unitPrice,specifications==null?Map.of():specifications);}
    }
    public record UploadedFile(String storagePath,String originalFilename,String mimeType,long fileSize,String checksum,String extractedText){}
    public record Summary(UUID id,String title,String priority,String status,int quotationCount,OffsetDateTime createdAt){}
    public record Header(UUID id,String title,String description,String category,String priority,String status,OffsetDateTime createdAt){}
    public record QuoteView(UUID id,int slot,String supplierName,String currency,BigDecimal subtotal,BigDecimal tax,BigDecimal shipping,BigDecimal additionalCharges,BigDecimal total,int deliveryDays,String warrantyTerms,String paymentTerms,String productDescription,BigDecimal quantity,BigDecimal unitPrice){}
    public record RiskView(int slot,String type,String severity,String description,String evidence,String recommendation){}
    public record Detail(Header comparison,List<QuoteView> quotations,List<RiskView> risks,Map<String,Object> result){}
}
