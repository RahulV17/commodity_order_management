import { Order } from './order.model';

export interface DashboardStats {
  totalOrders: number;
  pendingOrders: number;
  approvedOrders: number;
  rejectedOrders: number;
  cancelledOrders: number;
  newOrders: number;
  ordersByStatus: Record<string, number>;
  recentOrders: Order[];
}
