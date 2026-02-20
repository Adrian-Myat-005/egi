import express from 'express';
import cors from 'cors';
import bcrypt from 'bcryptjs';
import path from 'path';
import { sequelize, User } from './models';
import { generateToken, authenticateToken } from './auth';
import { getVpnConfig } from './vpn';

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, '../public')));

const PORT = process.env.PORT || 3000;

app.post('/api/auth/register', async (req, res) => {
  const { username, password } = req.body;
  try {
    const hashedPassword = await bcrypt.hash(password, 10);
    const user = await User.create({ username, password: hashedPassword });
    res.json({ token: generateToken(user), user: { username, isPremium: false } });
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
        expiry: user.subscriptionExpiry,
        assignedKey: user.assignedKey
      } 
    });
  } else {
    res.status(401).json({ error: 'INVALID_CREDENTIALS' });
  }
});

app.get('/api/vpn/config', authenticateToken, getVpnConfig);

// Public test key for users who haven't bought a VPS yet
app.get('/api/vpn/test-key', (req, res) => {
  const testKey = "ss://YWVzLTEyOC1nY206RWdpU2VjcmV0UGFzc3dvcmQyMDI2@159.223.1.1:8388";
  res.json({ config: testKey, message: "SAMPLE_TEST_KEY_FOR_EGI_SHIELD" });
});

// Admin-only: List all users for the control panel
app.get('/api/admin/users', async (req, res) => {
  const users = await User.findAll({ attributes: ['id', 'username', 'isPremium', 'subscriptionExpiry', 'assignedKey'] });
  res.json(users);
});

// Admin-only: Promote user and optionally assign a Shadowsocks key
app.post('/api/admin/promote', async (req, res) => {
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
    res.json({ success: true, user: { username, isPremium: true, expiry, assignedKey: user.assignedKey } });
  } else {
    res.status(404).json({ error: 'USER_NOT_FOUND' });
  }
});

// Admin-only: Reset user subscription
app.post('/api/admin/reset', async (req, res) => {
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

sequelize.sync().then(() => {
  app.listen(PORT, async () => {
    console.log(`EGI_CORE_API_RUNNING_ON_PORT_${PORT}`);
  });
});
