//
// Messaging Layer
//
// Messaging related code should reside in this file.
//
//


//
// NOTES (Brandon):
//
// * This is currently empty... but we may need a superclass in the future
//
export class TroubleFlipperMessage {
  constructor() {
  }
}

// Initial message sent from client upon choosing username.
//
export class UsersMessage extends TroubleFlipperMessage {
  constructor(username, clientId) {
    super();
    this.username = username;
    this.clientId = clientId;
  }

  getUsername() {
    return this.username;
  }

  getClientId() {
    return this.clientId;
  }

  print() {
    console.log(JSON.stringify(this));
  }
}

// Acknowledgement message from the server for UsersMessage.
//
export class UsersAckMessage extends UsersMessage {
  static get SUCCESS() {
    return "success";
  }
  static get FAILURE() {
    return "failure";
  }

  constructor(result, username, clientId) {
    super(username, clientId);
    this.result = result;
  }

  isSuccess() {
    if (this.result === UsersAckMessage.SUCCESS) {
      return true;
    } else {
      return false;
    }
  }

  isFailure() {
    return !isSuccess();
  }
}

// Message sent from adminstrative client to server to signal start of game
//
export class TournamentsMessage extends TroubleFlipperMessage {
  constructor() {
    super();
    this.action = 'buildTeams';
  }
}

export class TeamsMessage extends TroubleFlipperMessage {
  constructor(puzzle) {
    super();
    this.puzzle = puzzle;
  }

  getPuzzle() {
    return puzzle;
  }
}


// Should be called on received messages. Returns message object.
//
// NOTES (Brandon):
//
// * There is currently no validation of the json received... do we need this?
// * This function (and our messages currently) assumes that there is only one
//   type of message received on any given topic. This will probably be changed
//   in the future
//
export function parseReceivedMessage(topic, msg) {
  let msgObj = JSON.parse(msg);
  if (topic.startsWith('user/')) {
    return Object.assign(new UsersAckMessage, msgObj);
  } else if (topic.startsWith('team/')) {
    return Object.assign(new TeamsMessage, msgObj);
  } else {
    console.log('Unexpected topic', topic);
    return null;
  }
}

// Should be called for publishing messages. This function does not catch any
// exception thrown from session.send(), the caller should catch them instead.
//
export function publishMessageToTopic(topic, msg, session, solaceApi) {
  if (session !== null) {
    if (typeof msg !== 'object') {
      console.log('Invalid type for paramaeter: msg (' + msg + '), should be of type object');
      return;
    }
    var msgPayload = JSON.stringify(msg);
    console.log('Publishing msg (' + msgPayload + ') to topic (' + topic + ')');
    let solace = solaceApi;
    var solaceMessage = solace.SolclientFactory.createMessage();
    solaceMessage.setDestination(solace.SolclientFactory.createTopicDestination(topic));
    solaceMessage.setBinaryAttachment(msgPayload);
    solaceMessage.setDeliveryMode(solace.MessageDeliveryModeType.DIRECT);
    session.send(solaceMessage);
  }
}

//
// END MESSAGES
