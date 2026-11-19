export type FeeType = 'APPLICATION' | 'PERIODIC' | 'SURCHARGE';
export type FeeStatus = 'UNPAID' | 'PAID' | 'NON_PAYMENT';

export interface FeeInput {
  type: FeeType;
  applicationNumber: string;
  scheme: string;
  payer: string;
  payerEmail: string;
  description: string;
  amount: number;
  dueDate: string;
  sourceFeeId: number | null;
}

export interface Fee extends FeeInput {
  id: number;
  status: FeeStatus;
  settlementDate: string | null;
  paymentMethod: string | null;
  bankInDate: string | null;
  referenceNumber: string | null;
  receivedFrom: string | null;
}

export interface ImportResult {
  total: number;
  success: number;
  failed: number;
  rows: Array<{ row: number; reference: string; success: boolean; message: string }>;
}

export type DocumentKind = 'NOTICE' | 'REMINDER' | 'RECEIPT';

export interface FeeDocument {
  id: number;
  feeId: number;
  kind: DocumentKind;
  number: string;
  generatedAt: string;
  deliveryStatus: string;
  bankInDate?: string | null;
  valid?: boolean;
}

export interface FpsRequest {
  id: number;
  feeId: number;
  reference: string;
  expiresOn: string;
  status: string;
  qrText?: string;
}

export interface ReportData {
  columns: string[];
  rows: string[][];
}
