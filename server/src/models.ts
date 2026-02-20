import { Sequelize, DataTypes, Model } from 'sequelize';
import path from 'path';

const sequelize = new Sequelize({
  dialect: 'sqlite',
  storage: path.join(__dirname, '../database.sqlite'),
  logging: false,
});

export class User extends Model {
  declare id: number;
  declare username: string;
  declare password: string;
  declare isPremium: boolean;
  declare subscriptionExpiry: Date | null;
}

User.init({
  id: { type: DataTypes.INTEGER, autoIncrement: true, primaryKey: true },
  username: { type: DataTypes.STRING, unique: true, allowNull: false },
  password: { type: DataTypes.STRING, allowNull: false },
  isPremium: { type: DataTypes.BOOLEAN, defaultValue: false },
  subscriptionExpiry: { type: DataTypes.DATE, allowNull: true },
}, { sequelize, modelName: 'user' });

export class VpnNode extends Model {
  declare id: number;
  declare name: string;
  declare ip: string;
  declare port: number;
  declare ssPassword: string;
  declare method: string;
}

VpnNode.init({
  id: { type: DataTypes.INTEGER, autoIncrement: true, primaryKey: true },
  name: { type: DataTypes.STRING, allowNull: false },
  ip: { type: DataTypes.STRING, allowNull: false },
  port: { type: DataTypes.INTEGER, allowNull: false },
  ssPassword: { type: DataTypes.STRING, allowNull: false },
  method: { type: DataTypes.STRING, defaultValue: 'aes-128-gcm' },
}, { sequelize, modelName: 'vpn_node' });

export { sequelize };
