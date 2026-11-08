// Load generator for the CoreProtect migration test. Each bot works in its own area: places a ring of blocks,
// breaks them, moves one item in and out of a chest, chats and runs a command. Only actions the server confirmed
// are counted; counts go to stats.json every 10 s and on exit, to compare with CoreProtect's rows afterwards.
const fs = require('fs')
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

const HOST = process.env.HOST
const PORT = Number(process.env.PORT)
const COUNT = Number(process.env.BOTS || 12)
const DELAY = Number(process.env.DELAY || 250) // ms between actions per bot
const VERSION = process.env.MCVER || '26.1'
const BLOCKS = ['stone', 'oak_planks', 'cobblestone', 'glass', 'bricks', 'dirt']
const stats = {}
let stopping = false

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const save = () => fs.writeFileSync(require('path').join(__dirname, 'stats.json'),JSON.stringify({ at: new Date().toISOString(), stats }, null, 1))

// Ring around the bot's feet, within reach; (0, 2) holds the chest.
const RING = []
for (let dx = -2; dx <= 2; dx++) for (let dz = -2; dz <= 2; dz++) if (!(dx === 0 && dz === 0) && !(dx === 0 && dz === 2)) RING.push([dx, dz])

function startBot (i) {
  const name = 'LabBot' + String(i).padStart(2, '0')
  const s = stats[name] = stats[name] || { placed: 0, broken: 0, chest: 0, chat: 0, commands: 0, errors: 0, joins: 0, lastError: '' }
  // Physics off: the bots stand still (teleport with /tp), and movement packets through ViaBackwards got them kicked.
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, version: VERSION, auth: 'offline', physicsEnabled: process.env.PHYSICS === '1' })
  let alive = true
  // Drop the bot's own movement and look packets: Mineflayer's movement packet gets the bot kicked
  // ("invalid player movement") through ViaBackwards on every client version tried. The bots stand still,
  // teleports still get confirmed, and placing, breaking and chests are checked by distance, not rotation.
  const write = bot._client.write.bind(bot._client)
  bot._client.write = (packet, params) => {
    if (/^(position|position_look|look|flying)$/.test(packet)) { s.dropped = (s.dropped || 0) + 1; return }
    return write(packet, params)
  }
  const fail = e => { s.errors++; s.lastError = String(e && e.message || e).slice(0, 200) }

  bot.once('spawn', async () => {
    s.joins++
    try {
      const mcData = require('minecraft-data')(bot.version)
      const Item = require('prismarine-item')(bot.version)
      const X = i * 32
      await sleep(5000) // the server ignores block actions until it counts the client as loaded
      bot.chat(`/tp ${name} ${X} -60 0`)
      await sleep(1500)
      for (let k = 0; k < BLOCKS.length; k++) await bot.creative.setInventorySlot(36 + k, new Item(mcData.itemsByName[BLOCKS[k]].id, 64))
      await bot.creative.setInventorySlot(42, new Item(mcData.itemsByName.chest.id, 1))
      const base = bot.entity.position.floored()
      const chestPos = base.offset(0, 0, 2)
      if (bot.blockAt(chestPos).name !== 'chest') {
        await bot.equip(mcData.itemsByName.chest.id, 'hand')
        await bot.placeBlock(bot.blockAt(chestPos.offset(0, -1, 0)), new Vec3(0, 1, 0))
      }
      let cycle = 0
      while (alive && !stopping) {
        cycle++
        for (const [dx, dz] of RING) { // place
          if (!alive || stopping) break
          const target = base.offset(dx, 0, dz)
          try {
            if (bot.blockAt(target).name === 'air') {
              await bot.equip(mcData.itemsByName[BLOCKS[(cycle + dx + dz + 10) % BLOCKS.length]].id, 'hand')
              await bot.placeBlock(bot.blockAt(target.offset(0, -1, 0)), new Vec3(0, 1, 0))
              s.placed++
            }
          } catch (e) { fail(e) }
          await sleep(DELAY)
        }
        for (const [dx, dz] of RING) { // break
          if (!alive || stopping) break
          const block = bot.blockAt(base.offset(dx, 0, dz))
          try {
            if (block && block.name !== 'air') {
              await bot.dig(block, true)
              s.broken++
            }
          } catch (e) { fail(e) }
          await sleep(DELAY)
        }
        if (!alive || stopping) break
        try { // chest: one item in, then back out (logs nothing: CoreProtect stores the net change)
          const chest = await bot.openContainer(bot.blockAt(chestPos))
          await chest.deposit(mcData.itemsByName.stone.id, null, 1)
          await chest.withdraw(mcData.itemsByName.stone.id, null, 1)
          if (process.env.DEPOSIT === '1') {
            // Deposit mode: everything that is not a building block or the chest stays in the chest, so container
            // rows (with item metadata for named or enchanted items given from the console) are written.
            for (const item of bot.inventory.items()) {
              if (!BLOCKS.includes(item.name) && item.name !== 'chest') {
                await chest.deposit(item.type, null, item.count)
                s.deposited = (s.deposited || 0) + item.count
              }
            }
          }
          chest.close()
          s.chest++
        } catch (e) { fail(e) }
        bot.chat(`cycle ${cycle}`); s.chat++
        if (cycle % 5 === 0) { bot.chat('/list'); s.commands++ }
      }
    } catch (e) { fail(e) }
  })
  bot.on('kicked', reason => fail('kicked: ' + JSON.stringify(reason)))
  bot.on('error', fail)
  bot.on('end', () => {
    alive = false
    if (!stopping) setTimeout(() => startBot(i), 5000) // rejoin
  })
  return bot
}

const bots = []
;(async () => {
  for (let i = 1; i <= COUNT; i++) { bots.push(startBot(i)); await sleep(1500) }
})()
setInterval(save, 10000)
process.on('SIGTERM', async () => {
  stopping = true
  await sleep(3000) // let running actions finish and be counted
  save()
  for (const b of bots) try { b.quit() } catch (e) {}
  setTimeout(() => process.exit(0), 1000)
})
