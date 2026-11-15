export type OrderType = 'BUY' | 'SELL';

export type OrderStatus = 'NEW' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface Order {
  id: number;
  orderNumber: string;
  instrumentId: string;
  instrumentSymbol: string;
  instrumentName: string;
  orderType: OrderType;
  quantity: number;
  price: number;
  status: OrderStatus;
  remarks: string | null;
  rejectionReason: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateOrderRequest {
  instrumentId: string;
  orderType: OrderType;
  quantity: number;
  price: number;
  remarks?: string | null;
}

export interface RejectOrderRequest {
  reason: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface OrderQuery {
  instrumentId?: string;
  status?: OrderStatus | '';
  search?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface OrderDocument {
  id: number;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedBy: string;
  uploadedAt: string;
}
