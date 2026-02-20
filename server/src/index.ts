import express from 'express';
import cors from 'cors';
import bcrypt from 'bcryptjs';
import path from 'path';
import { sequelize, User, Node } from './models';
import { generateToken, authenticateToken } from './auth';
import { getVpnConfig } from './vpn';

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, '../public')));

const PORT = process.env.PORT || 3000;
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'EgiAdmin2026';

const authenticateAdmin = (req: express.Request, res: express.Response, next: express.NextFunction) => {
    const adminAuth = req.headers['x-admin-auth'];
    if (adminAuth === ADMIN_PASSWORD) {
        next();
    } else {
        res.status(401).json({ error: 'ADMIN_UNAUTHORIZED' });
    }
};

app.post('/api/auth/register', async (req, res) => {
  const { username, password } = req.body;
  try {
    const hashedPassword = await bcrypt.hash(password, 10);
    const user = await User.create({ username, password: hashedPassword });
    res.json({ token: generateToken(user), user: { username, isPremium: false, expiry: 0 } });
  } catch (error) {
    res.status(400).json({ error: 'USER_ALREADY_EXISTS' });
  }
});

app.post('/api/auth/login', async (req, res) => {
  const { username, password } = req.body;
  const user = await User.findOne({ where: { username } });
  if (user && await bcrypt.compare(password, user.password)) {
    res.json({ 
      token: generateToken(user), 
      user: { 
        username, 
        isPremium: user.isPremium,
        expiry: user.subscriptionExpiry ? user.subscriptionExpiry.getTime() : 0,
        assignedKey: user.assignedKey
      } 
    });
  } else {
    res.status(401).json({ error: 'INVALID_CREDENTIALS' });
  }
});

app.get('/api/vpn/config', authenticateToken, getVpnConfig);

// Public: List regions (Premium only)
app.get('/api/vpn/regions', authenticateToken, async (req, res) => {
    try {
        const user = await User.findByPk((req as any).user.id);
        if (!user || !user.isPremium) return res.status(403).json({ error: 'PREMIUM_REQUIRED' });
        const nodes = await Node.findAll({ attributes: ['id', 'regionName'] });
        res.json(nodes);
    } catch (e) { res.status(500).json({ error: 'FAILED_TO_LOAD_REGIONS' }); }
});

// Admin: Manage Regions (Nodes)
app.get('/api/admin/nodes', authenticateAdmin, async (req, res) => {
    const nodes = await Node.findAll();
    res.json(nodes);
});

app.post('/api/admin/nodes/add', authenticateAdmin, async (req, res) => {
    const { regionName, ssKey } = req.body;
    const node = await Node.create({ regionName, ssKey });
    res.json({ success: true, node });
});

app.post('/api/admin/nodes/delete', authenticateAdmin, async (req, res) => {
    const { id } = req.body;
    await Node.destroy({ where: { id } });
    res.json({ success: true });
});

// Public test key for users who haven't bought a VPS yet
app.get('/api/vpn/test-key', (req, res) => {
  const testKey = "ss://YWVzLTEyOC1nY206RWdpU2VjcmV0UGFzc3dvcmQyMDI2@159.223.1.1:8388";
  res.json({ config: testKey, message: "SAMPLE_TEST_KEY_FOR_EGI_SHIELD" });
});

// Admin-only: List all users for the control panel
app.get('/api/admin/users', authenticateAdmin, async (req, res) => {
  const users = await User.findAll({ attributes: ['id', 'username', 'isPremium', 'subscriptionExpiry', 'assignedKey'] });
  res.json(users);
});

// Admin-only: Promote user and optionally assign a Shadowsocks key
app.post('/api/admin/promote', authenticateAdmin, async (req, res) => {
  const { username, months, ssKey } = req.body;
  const user = await User.findOne({ where: { username } });
  if (user) {
    const expiry = new Date();
    expiry.setMonth(expiry.getMonth() + (months || 1));
    user.isPremium = true;
    user.subscriptionExpiry = expiry;
    if (ssKey) {
        user.assignedKey = ssKey;
    }
    await user.save();
    res.json({ success: true, user: { 
        username, 
        isPremium: true, 
        expiry: expiry.getTime(), 
        assignedKey: user.assignedKey 
    } });
  } else {
    res.status(404).json({ error: 'USER_NOT_FOUND' });
  }
});

// Admin-only: Reset user subscription
app.post('/api/admin/reset', authenticateAdmin, async (req, res) => {
  const { username } = req.body;
  const user = await User.findOne({ where: { username } });
  if (user) {
    user.isPremium = false;
    user.subscriptionExpiry = null;
    user.assignedKey = null;
    await user.save();
    res.json({ success: true, message: "USER_SUBSCRIPTION_RESET" });
  } else {
    res.status(404).json({ error: 'USER_NOT_FOUND' });
  }
});

// Admin-only: Permanent user deletion
app.post('/api/admin/delete', authenticateAdmin, async (req, res) => {
    const { username } = req.body;
    const user = await User.findOne({ where: { username } });
    if (user) {
      await user.destroy();
      res.json({ success: true, message: "USER_DELETED" });
    } else {
      res.status(404).json({ error: 'USER_NOT_FOUND' });
    }
});

// Admin-only: Delete user
app.post('/api/admin/delete', authenticateAdmin, async (req, res) => {
  const { username } = req.body;
  const user = await User.findOne({ where: { username } });
  if (user) {
    await user.destroy();
    res.json({ success: true, message: "USER_DELETED" });
  } else {
    res.status(404).json({ error: 'USER_NOT_FOUND' });
  }
});

sequelize.sync().then(() => {
  app.listen(PORT, async () => {
    console.log(`EGI_CORE_API_RUNNING_ON_PORT_${PORT}`);
  });
});
