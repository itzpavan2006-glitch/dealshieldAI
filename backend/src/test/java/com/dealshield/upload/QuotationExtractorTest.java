package com.dealshield.upload;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuotationExtractorTest {
 @Test void realQuotationPdfs() throws Exception {
  var folder=java.nio.file.Path.of("C:/Users/Administrator/Documents");
  org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(folder.resolve("DealShield_Dummy2.pdf"))&&java.nio.file.Files.isRegularFile(folder.resolve("Supplier_Quotation DUMMY.pdf")));
  var parser=new QuotationExtractor();
  var tech=parser.extract(java.nio.file.Files.readAllBytes(folder.resolve("DealShield_Dummy2.pdf")),"application/pdf");
  assertEquals("TechZone Solutions",tech.supplierName());assertEquals("TZS-DEMO-2026-001",tech.quotationNumber());assertEquals(new BigDecimal("10"),tech.quantity());assertEquals(new BigDecimal("45000.00"),tech.unitPrice());assertEquals(new BigDecimal("81000.00"),tech.tax());assertEquals(new BigDecimal("536000.00"),tech.grandTotal());assertEquals(new BigDecimal("18"),tech.taxRate());assertEquals("16 GB DDR4",tech.specifications().get("ram"));
  var apex=parser.extract(java.nio.file.Files.readAllBytes(folder.resolve("Supplier_Quotation DUMMY.pdf")),"application/pdf");
  assertEquals("APEX SYSTEMS",apex.supplierName());assertEquals(new BigDecimal("20"),apex.quantity());assertEquals(new BigDecimal("57500"),apex.unitPrice());assertEquals(new BigDecimal("207000"),apex.tax());assertEquals(new BigDecimal("1357000"),apex.grandTotal());assertEquals(new BigDecimal("18"),apex.taxRate());assertEquals("16 GB DDR5",apex.specifications().get("memory"));
 }
 @Test void extractsOnlyPresentCommercialFields(){var d=new QuotationExtractor().parse("Supplier: Acme Systems\nSubtotal: INR 45,000.00\nGST (18%): 8,100\nShipping: 500\nDelivery Time: 7 days\nWarranty: 24 months\nPayment Terms: Net 30 days\n",List.of());assertEquals("Acme Systems",d.supplierName());assertEquals(new BigDecimal("45000.00"),d.subtotal());assertEquals(new BigDecimal("8100"),d.tax());assertEquals("7 days",d.deliveryTerms());assertEquals("24 months",d.warrantyTerms());assertEquals("Net 30 days",d.paymentTerms());}
 @Test void missingValuesRemainNull(){var d=new QuotationExtractor().parse("Quotation without labelled totals",List.of());assertNull(d.supplierName());assertNull(d.subtotal());assertNull(d.tax());assertNull(d.shipping());}
 @Test void recognizesHeadingAndDoesNotTreatGstRateAsAmount(){var d=new QuotationExtractor().parse("Apex Systems\nQuotation\nSubtotal: INR 1,150,000\nGST 18%\nGrand Total: INR 1,357,000\n",List.of());assertEquals("Apex Systems",d.supplierName());assertEquals(new BigDecimal("207000"),d.tax());}
 @Test void recognizesLabeledSupplierAndExplicitTaxAmount(){var d=new QuotationExtractor().parse("Supplier: TechZone Solutions\nSubtotal: INR 450,000\nGST (18%): INR 81,000\nShipping: INR 5,000\n",List.of());assertEquals("TechZone Solutions",d.supplierName());assertEquals(new BigDecimal("81000"),d.tax());assertEquals(new BigDecimal("5000"),d.shipping());}
 @Test void flagsInconsistentArithmeticWithoutReplacingSourceValues(){var d=new QuotationExtractor().parse("Supplier: Example Systems\nProduct Name: Laptop\nQuantity: 10\nUnit Price: 100\nSubtotal: 900\nGST 18%: 162\nShipping: 10\nOther Charges: 0\nGrand Total: 1000\n",List.of());assertEquals(new BigDecimal("900"),d.subtotal());assertEquals(new BigDecimal("162"),d.tax());assertTrue(d.warnings().stream().anyMatch(x->x.contains("unit price")));assertTrue(d.warnings().stream().anyMatch(x->x.contains("Quoted total")));}
}
