import { User } from './models';
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

    // If the user has a specific Outline key assigned, use that!
    if (user.assignedKey && user.assignedKey.startsWith('ss://')) {
      return res.json({
        node_name: "EGI_ONE_CLICK_VPN",
        config: user.assignedKey,
        expiry: user.subscriptionExpiry,
      });
    }

    return res.status(503).json({ error: 'NO_KEY_ASSIGNED', message: 'PLEASE_CONTACT_SUPPORT' });

  } catch (error) {
    res.status(500).json({ error: 'INTERNAL_SERVER_ERROR' });
  }
};
