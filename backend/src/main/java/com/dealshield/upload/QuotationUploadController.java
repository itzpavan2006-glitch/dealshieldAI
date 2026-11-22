package com.dealshield.upload;

import com.dealshield.auth.SessionCredentials;
import com.dealshield.security.VerifiedBuyer;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/quotation-uploads")
public class QuotationUploadController {
 private static final int MAX=10*1024*1024;
 private final ObjectProvider<SupabaseStorageClient> storage;private final QuotationExtractor extractor;
 public QuotationUploadController(ObjectProvider<SupabaseStorageClient> storage,QuotationExtractor extractor){this.storage=storage;this.extractor=extractor;}
 @PostMapping(value="/extract",consumes="multipart/form-data")
 public UploadDraft upload(@RequestPart("file")MultipartFile file,Authentication authentication,HttpServletRequest request)throws Exception{
  if(!(authentication.getPrincipal() instanceof VerifiedBuyer buyer))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  var session=request.getSession(false);if(session==null||!(session.getAttribute(SessionCredentials.KEY) instanceof SessionCredentials credentials)||!credentials.buyerId().equals(buyer.authUserId()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  byte[] bytes=file.getBytes();if(bytes.length==0||bytes.length>MAX)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"File must be between 1 byte and 10 MB");
  String mime=detect(bytes);String original=safeName(file.getOriginalFilename(),mime);String path=buyer.authUserId()+"/"+UUID.randomUUID()+"/"+original;
  var client=storage.getIfAvailable();if(client==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Storage is not configured");
  try{client.upload(credentials.accessToken(),path,mime,bytes);}catch(SupabaseStorageClient.StorageFailure e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Private storage upload is not ready");}
  var draft=extractor.extract(bytes,mime);String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  return new UploadDraft(path,original,mime,bytes.length,checksum,draft.extractedText(),draft.supplierName(),draft.subtotal(),draft.tax(),draft.shipping(),draft.deliveryTerms(),draft.warrantyTerms(),draft.paymentTerms(),draft.warnings(),draft.quotationNumber(),draft.quotationDate(),draft.validityTerms(),draft.productName(),draft.model(),draft.category(),draft.quantity(),draft.unitPrice(),draft.taxRate(),draft.otherCharges(),draft.grandTotal(),draft.specifications());
 }
 private String detect(byte[] b){if(b.length>=5&&b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F'&&b[4]=='-')return "application/pdf";if(b.length>=8&&(b[0]&255)==137&&b[1]=='P'&&b[2]=='N'&&b[3]=='G')return "image/png";if(b.length>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255)return "image/jpeg";throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Only PDF, JPG and PNG files are allowed");}
 private String safeName(String n,String mime){String fallback="quotation."+(mime.equals("application/pdf")?"pdf":mime.equals("image/png")?"png":"jpg");if(n==null||n.isBlank())return fallback;String safe=n.replaceAll("[^A-Za-z0-9._-]","_");return safe.length()>120?safe.substring(safe.length()-120):safe;}
 public record UploadDraft(String storagePath,String originalFilename,String mimeType,long fileSize,String checksum,String extractedText,String supplierName,java.math.BigDecimal subtotal,java.math.BigDecimal tax,java.math.BigDecimal shipping,String deliveryTerms,String warrantyTerms,String paymentTerms,List<String>warnings,String quotationNumber,String quotationDate,String validityTerms,String productName,String model,String category,java.math.BigDecimal quantity,java.math.BigDecimal unitPrice,java.math.BigDecimal taxRate,java.math.BigDecimal otherCharges,java.math.BigDecimal grandTotal,java.util.Map<String,String> specifications){}
}
