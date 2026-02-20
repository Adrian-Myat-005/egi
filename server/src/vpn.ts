import { VpnNode, User } from './models';
import { Request, Response } from 'express';

export const getVpnConfig = async (req: Request, res: Response) => {
  try {
    const user = await User.findByPk((req as any).user.id);
    if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' });

    // Premium check
    const now = new Date();
    if (!user.isPremium || (user.subscriptionExpiry && user.subscriptionExpiry < now)) {
      return res.status(403).json({ error: 'PREMIUM_REQUIRED', message: 'YOUR_SUBSCRIPTION_HAS_EXPIRED' });
    }

    const nodes = await VpnNode.findAll();
    if (nodes.length === 0) return res.status(503).json({ error: 'NO_NODES_AVAILABLE' });

    // Simple load balancing: pick a random node
    const node = nodes[Math.floor(Math.random() * nodes.length)];

    // Construct SIP002 Shadowsocks URL (Standard)
    // ss://method:password@host:port
    const userInfo = Buffer.from(`${node.method}:${node.ssPassword}`).toString('base64');
    const ssUrl = `ss://${userInfo}@${node.ip}:${node.port}`;

    res.json({
      node_name: node.name,
      config: ssUrl,
      expiry: user.subscriptionExpiry,
    });
  } catch (error) {
    res.status(500).json({ error: 'INTERNAL_SERVER_ERROR' });
  }
};
